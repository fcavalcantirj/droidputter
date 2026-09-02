#!/usr/bin/env python3
"""DROIDPUTTER host receiver: renders the ESP's USB-CDC display stream, injects keys, records fixtures.
Wire: D7 50 | type | len u16 LE | payload | crc8(0x07 over type+len+payload). See docs/PROTOCOL.md.
Usage: dp_receiver.py [--port /dev/cu.usbmodemX] [--record NAME] [--png DIR] [--selftest]
stdin: `k ROW,COL` press+release, `d ROW,COL`/`u ROW,COL` down/up, `p` save png now, `s` stats, `q` quit."""
import argparse, glob, json, os, struct, sys, threading, time, zlib
HELLO, FILL, RECT, RECT_RLE, STATS, PING = 1, 2, 3, 4, 5, 6
KEY, GPS_NMEA, PING_IN, HELLO_ACK = 0x81, 0x82, 0x83, 0x84

def crc8(data, c=0):
    for b in data:
        c ^= b
        for _ in range(8): c = ((c << 1) ^ 0x07) & 0xFF if c & 0x80 else (c << 1) & 0xFF
    return c

def frame(t, payload=b""):
    hdr = bytes([0xD7, 0x50, t]) + struct.pack("<H", len(payload))
    return hdr + payload + bytes([crc8(hdr[2:] + payload)])

class Framer:
    def __init__(self): self.buf = bytearray(); self.text = bytearray(); self.bad = 0
    def feed(self, data):
        self.buf += data; out = []
        while True:
            i = self.buf.find(b"\xD7\x50")
            if i < 0:
                self.text += self.buf; self.buf.clear(); break
            if i: self.text += self.buf[:i]; del self.buf[:i]
            if len(self.buf) < 6: break
            t = self.buf[2]; ln = struct.unpack_from("<H", self.buf, 3)[0]
            if len(self.buf) < 6 + ln: break
            if crc8(self.buf[2:5 + ln]) == self.buf[5 + ln]:
                out.append((t, bytes(self.buf[5:5 + ln]))); del self.buf[:6 + ln]
            else:
                self.bad += 1; self.text += self.buf[:2]; del self.buf[:2]
        return out
    def take_text(self):
        t = bytes(self.text); self.text.clear(); return t

class Screen:
    def __init__(self, w=240, h=135): self.resize(w, h)
    def resize(self, w, h): self.w, self.h = w, h; self.px = bytearray(w * h * 2)
    def put(self, x, y, w, h, data):  # data = w*h*2 bytes, panel order (big-endian 565)
        for r in range(h):
            yy = y + r
            if yy >= self.h: break
            cw = min(w, self.w - x)
            if cw <= 0: break
            o = (yy * self.w + x) * 2; self.px[o:o + cw * 2] = data[r * w * 2:(r * w + cw) * 2]
    def png(self, path):
        rows = []
        for y in range(self.h):
            row = bytearray([0])
            for x in range(self.w):
                v = (self.px[(y * self.w + x) * 2] << 8) | self.px[(y * self.w + x) * 2 + 1]
                row += bytes(((v >> 11 & 31) * 255 // 31, (v >> 5 & 63) * 255 // 63, (v & 31) * 255 // 31))
            rows.append(bytes(row))
        raw = b"".join(rows)
        def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
        with open(path, "wb") as f:
            f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))

def apply(scr, t, p, stats):
    if t == HELLO and len(p) >= 7:
        w, h = struct.unpack_from("<HH", p, 1); scr.resize(w, h)
        stats["hello"] = dict(w=w, h=h, rot=p[5], bpp=p[6], board=p[7:23].rstrip(b"\0").decode(), app=p[23:55].rstrip(b"\0").decode())
        return "HELLO %s" % stats["hello"]
    if t == FILL and len(p) == 10:
        x, y, w, h = struct.unpack_from("<HHHH", p); scr.put(x, y, w, h, bytes(p[8:10]) * (w * h)); return "FILL %d,%d %dx%d" % (x, y, w, h)
    if t == RECT and len(p) >= 8:
        x, y, w, h = struct.unpack_from("<HHHH", p)
        if len(p) - 8 != w * h * 2: return "RECT bad size"
        scr.put(x, y, w, h, p[8:]); return "RECT %d,%d %dx%d" % (x, y, w, h)
    if t == RECT_RLE and len(p) >= 8:
        x, y, w, h = struct.unpack_from("<HHHH", p); out = bytearray()
        for i in range(8, len(p) - 2, 3): out += bytes(p[i + 1:i + 3]) * p[i]
        if len(out) != w * h * 2: return "RLE bad expand %d vs %d" % (len(out), w * h * 2)
        scr.put(x, y, w, h, out); return "RECT_RLE %d,%d %dx%d (%dB)" % (x, y, w, h, len(p))
    if t == STATS and len(p) == 16:
        f, b, d, heap = struct.unpack("<IIII", p); stats["esp"] = dict(frames=f, bytes=b, dropped=d, heap=heap); return "STATS frames=%d bytes=%d dropped=%d heap=%d" % (f, b, d, heap)
    if t == PING: return "PONG"
    return "type %d len %d" % (t, len(p))

def decode_file(path):
    fr, scr, st = Framer(), Screen(), {}
    with open(path, "rb") as f: data = f.read()
    counts = {}
    for t, p in fr.feed(data):
        msg = apply(scr, t, p, st); name = msg.split()[0]
        counts[name] = counts.get(name, 0) + 1
        print(msg)
    print("counts", counts, "bad", fr.bad)
    return 0

def selftest():
    fr = Framer(); s = Screen(4, 2); st = {}
    data = b"noise\n" + frame(HELLO, bytes([0]) + struct.pack("<HH", 4, 2) + bytes([1, 16]) + b"b".ljust(16, b"\0") + b"a".ljust(32, b"\0"))
    data += frame(FILL, struct.pack("<HHHH", 0, 0, 4, 2) + b"\xF8\x00")
    r = frame(RECT, struct.pack("<HHHH", 1, 1, 2, 1) + b"\x07\xE0\x00\x1F")
    fr_out = fr.feed(data + r[:7]); fr_out += fr.feed(r[7:] + b"\xD7\x50\x03\x02\x00zz\x00")
    names = [apply(s, t, p, st) for t, p in fr_out]
    assert len(fr_out) == 3, names; assert s.px[(1 * 4 + 1) * 2:(1 * 4 + 3) * 2] == b"\x07\xE0\x00\x1F", s.px
    assert fr.bad == 1; s.png("/tmp/dp_selftest.png"); print("selftest ok", names); return 0

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--port"); ap.add_argument("--record"); ap.add_argument("--png"); ap.add_argument("--key"); ap.add_argument("--selftest", action="store_true"); ap.add_argument("--decode"); a = ap.parse_args()
    if a.selftest: sys.exit(selftest())
    if a.decode: sys.exit(decode_file(a.decode))
    import serial
    port = a.port or (glob.glob("/dev/cu.usbmodem*") + [None])[0]
    if not port: sys.exit("no /dev/cu.usbmodem* — plug the ESP (native USB) first")
    ser = serial.Serial(port, 115200, timeout=0.02); ser.dtr = True; ser.rts = True
    fr, scr, st = Framer(), Screen(), {"frames": 0, "bytes": 0, "last_key_t": None}
    t0 = time.monotonic(); lock = threading.Lock(); run = [True]
    rec = open(a.record + ".bin", "ab") if a.record else None; recj = open(a.record + ".jsonl", "a") if a.record else None
    if a.png: os.makedirs(a.png, exist_ok=True)
    def reader():
        last_png = 0; sec_bytes = 0; sec_t = time.monotonic()
        while run[0]:
            d = ser.read(65536)
            if not d:
                if time.monotonic() - sec_t >= 1: print("  [%.0f B/s] frames=%d bad=%d" % (sec_bytes / (time.monotonic() - sec_t), st["frames"], fr.bad)); sec_bytes = 0; sec_t = time.monotonic()
                continue
            now = time.monotonic(); sec_bytes += len(d); st["bytes"] += len(d)
            if rec: rec.write(d); recj.write(json.dumps({"t_ms": round((now - t0) * 1000, 1), "dir": "in", "n": len(d)}) + "\n")
            with lock:
                for t, p in fr.feed(d):
                    st["frames"] += 1; msg = apply(scr, t, p, st)
                    if st["last_key_t"] is not None and t in (FILL, RECT, RECT_RLE): print("  -> first draw %.1f ms after key" % ((now - st["last_key_t"]) * 1000)); st["last_key_t"] = None
                    if t != STATS or True: print("  %8.1f %s" % ((now - t0) * 1000, msg))
                txt = fr.take_text().decode("utf-8", "replace").strip()
                if txt: print("  << " + txt[:160])
            if a.png and now - last_png > 1: scr.png(os.path.join(a.png, "screen.png")); last_png = now
            if time.monotonic() - sec_t >= 1: print("  [%.0f B/s] frames=%d bad=%d" % (sec_bytes / (time.monotonic() - sec_t), st["frames"], fr.bad)); sec_bytes = 0; sec_t = time.monotonic()
    def send(t, payload=b""):
        f = frame(t, payload); ser.write(f)
        if recj: recj.write(json.dumps({"t_ms": round((time.monotonic() - t0) * 1000, 1), "dir": "out", "hex": f.hex()}) + "\n")
    threading.Thread(target=reader, daemon=True).start()
    print("port", port); send(HELLO_ACK, struct.pack("<HH", 1080, 2400))
    if a.key:
        r, c = [int(v) for v in a.key.split(",")]
        st["last_key_t"] = time.monotonic()
        send(KEY, bytes([r, c, 1])); time.sleep(0.08); send(KEY, bytes([r, c, 0]))
        time.sleep(0.5); run[0] = False; time.sleep(0.1); ser.close()
        print("bytes", st["bytes"], "frames", st["frames"], "bad", fr.bad, st.get("esp")); return
    try:
        for line in sys.stdin:
            line = line.strip()
            if line == "q": break
            if line == "p": scr.png("/tmp/dp_screen.png"); print("saved /tmp/dp_screen.png"); continue
            if line == "s": print(st); continue
            if line == "h": send(HELLO_ACK, struct.pack("<HH", 1080, 2400)); continue
            if line[:1] in "kdu" and "," in line:
                r, c = [int(v) for v in line[1:].split(",")]
                st["last_key_t"] = time.monotonic()
                if line[0] in "kd": send(KEY, bytes([r, c, 1]))
                if line[0] == "k": time.sleep(0.08)
                if line[0] in "ku": send(KEY, bytes([r, c, 0]))
                continue
            print("?", line)
    finally:
        run[0] = False; time.sleep(0.1); ser.close(); print("bytes", st["bytes"], "frames", st["frames"], "bad", fr.bad, st.get("esp"))
if __name__ == "__main__": main()
