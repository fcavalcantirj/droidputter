# Mac triage of a silent Cardputer: raw read, then PING_IN probe, then esptool SYNC probe. No reset, no flash.
import glob, time, serial, struct, re, sys
sys.path.insert(0, '/Users/fcavalcanti/dev/droidputter/tools'); import dp_receiver as dp
port = glob.glob('/dev/cu.usbmodem*')[0]
s = serial.Serial(port, 115200, timeout=0.1); s.dtr = True; s.rts = True
def read(sec):
    t0 = time.time(); buf = b''
    while time.time() - t0 < sec: buf += s.read(65536)
    return buf
def summarize(tag, buf):
    txt = re.sub(rb'[^\x20-\x7e\r\n]', b'.', buf)
    stats = []
    i = 0
    while True:
        j = buf.find(b'\xd7\x50', i)
        if j < 0 or j + 6 > len(buf): break
        t = buf[j+2]; ln = buf[j+3] | (buf[j+4] << 8); p = buf[j+5:j+5+ln]
        if t == 5 and ln == 16: stats.append(struct.unpack('<IIII', p))
        i = j + 5 + ln + 1
    sync = buf.count(b'\xd7\x50'); more = '...' if len(stats) > 2 else ''; last = stats[-1] if stats else None
    print(f"[{tag}] bytes={len(buf)} sync={sync} stats={stats[:2]}{more} last={last}")
    for k in [b'waiting for download', b'ESP-ROM', b'rst:', b'boot:', b'Guru', b'Backtrace', b'abort']:
        if buf.count(k): print(f"   found {k!r} x{buf.count(k)}")
    if txt.strip(): print("   head:", txt[:200].decode())
summarize('idle 5s', read(5))
s.write(dp.frame(0x83)); summarize('after PING_IN 2s', read(2))
SYNC = bytes([0xC0,0x00,0x08,0x24,0,0,0,0,0,0x07,0x07,0x12,0x20]) + b'\x55'*32 + b'\xC0'
s.write(SYNC); s.write(SYNC); summarize('after esptool SYNC 2s', read(2))
s.close()
