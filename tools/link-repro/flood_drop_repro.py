# Storm (linked tee) + inbound NMEA flood, then a host drop: 'close' (stop reading 5 s) or 'reenum' (IOKit bus reset). Then probe.
import glob, time, serial, struct, sys, os, subprocess, threading
sys.path.insert(0, '/Users/fcavalcanti/dev/droidputter/tools'); import dp_receiver as dp
SYNC=b'\xd7\x50'; HERE=os.path.dirname(os.path.abspath(__file__))
def nf(b): return b.count(SYNC)
def rd(s, sec):
    t0=time.time(); b=b''
    while time.time()-t0 < sec:
        try: b += s.read(65536)
        except Exception as e: print("read err", e); break
    return b
def stats(b):
    out=[]; i=0
    while True:
        j=b.find(SYNC, i)
        if j<0 or j+6>len(b): break
        t=b[j+2]; ln=b[j+3]|(b[j+4]<<8); p=b[j+5:j+5+ln]
        if t==5 and ln==16: out.append(struct.unpack('<IIII',p))
        elif t==7: print("   LOG:", p.decode(errors='replace'))
        elif t==1: print("   HELLO")
        i=j+5+ln+1
    return out
def port():
    g=glob.glob('/dev/cu.usbmodem*'); return g[0] if g else None
def wait_port(present, timeout=15):
    t0=time.time()
    while time.time()-t0 < timeout:
        if (port() is not None) == present: return True
        time.sleep(0.1)
    return False
def cs(body):
    c=0
    for x in body.encode(): c ^= x
    return f'${body}*{c:02X}'
SENT=[cs('GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,'), cs('GPRMC,123519,A,4807.038,N,01131.000,E,000.5,054.7,030926,020.3,E'),
      cs('GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1'), cs('GPVTG,054.7,T,034.4,M,005.5,N,010.2,K'),
      cs('PMTKRTK1,0,-522,-259200,0,0,0.000,0000,0.0000000,0.0000000,0.000,0.000,0.000,0.000,0.000,0.000,0.000,0.000,0.000,0.000,0,0,0,0,99,0,0,0,0,4,0')]
for i in range(1,9):
    for tk in ('GP','GL','GA','BD'): SENT.append(cs(f'{tk}GSV,8,{i},32,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45'))
class Flood(threading.Thread):
    def __init__(self, s): super().__init__(daemon=True); self.s=s; self.stop=False; self.n=0
    def run(self):
        while not self.stop:
            for snt in SENT:
                if self.stop: break
                try: self.s.write(dp.frame(0x82, snt.encode())); self.n+=1
                except Exception: return
                time.sleep(1/40)
mode = sys.argv[1] if len(sys.argv)>1 else 'close'
s = serial.Serial(port(), 115200, timeout=0.05); s.dtr=True; s.rts=True
s.write(dp.frame(0x84, struct.pack('<HH',1080,2400)))
fl = Flood(s); fl.start()
b = rd(s, 4); st = stats(b); print(f"storm+flood 4s: frames={nf(b)} sent={fl.n} STATS={st[-1] if st else None}")
if mode == 'close':
    s.close(); print("host stops reading 5 s (flood thread dies with the port)"); time.sleep(5)
else:
    fl.stop=False  # keep flooding right up to the reset
    args=[HERE+'/reenum'] + (['suspend', sys.argv[2]] if mode=='suspend' else [])
    r = subprocess.run(args, capture_output=True, text=True); print("reenum/suspend (port open, flooding):", r.stdout.strip().replace('\n',' '))
    fl.stop=True
    try: s.close()
    except Exception as e: print("close err", e)
    if mode!='suspend': print("port gone:", wait_port(False, 5), "| port back:", wait_port(True, 15))
    time.sleep(0.8)
fl.stop=True
s = serial.Serial(port(), 115200, timeout=0.05); s.dtr=True; s.rts=True
b = rd(s, 2); st = stats(b); print(f"after reopen idle 2s: bytes={len(b)} frames={nf(b)} STATS={st[-1] if st else None}")
for i in range(14):
    s.write(dp.frame(0x83)); b = rd(s, 1); st = stats(b)
    print(f"PING_IN #{i+1}: bytes={len(b)} frames={nf(b)} STATS={st[-1] if st else None}")
s.close()
