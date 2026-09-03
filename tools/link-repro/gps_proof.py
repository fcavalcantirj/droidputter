# GPS proof on the Mac: HELLO_ACK -> link, send NMEA, render the ESP's screen to PNG.
# usage: gps_proof.py canonical|flood [seconds]
import sys, time, glob, serial, struct, os
sys.path.insert(0, '/Users/fcavalcanti/dev/droidputter/tools')
import dp_receiver as dp
mode = sys.argv[1] if len(sys.argv) > 1 else 'canonical'
secs = float(sys.argv[2]) if len(sys.argv) > 2 else 8
out = os.path.dirname(os.path.abspath(__file__))
port = glob.glob('/dev/cu.usbmodem*')[0]
s = serial.Serial(port, 115200, timeout=0.05); s.dtr = True; s.rts = True
fr = dp.Framer(); scr = dp.Screen(); stats = {}
counts = {}; first_stats = None; last_stats = None; log = []
def pump(t_end):
    global first_stats, last_stats
    while time.time() < t_end:
        data = s.read(65536)
        if not data: continue
        for t, p in fr.feed(data):
            msg = dp.apply(scr, t, p, stats)
            counts[msg.split()[0]] = counts.get(msg.split()[0], 0) + 1
            if t == dp.STATS:
                if first_stats is None: first_stats = dict(stats['esp'])
                last_stats = dict(stats['esp'])
            elif t == dp.HELLO: log.append(msg)
GPS_NMEA = 0x82; HELLO_ACK = 0x84
s.write(dp.frame(HELLO_ACK, struct.pack('<HH', 1080, 2400)))
pump(time.time() + 1.5)
if mode == 'canonical':
    s.write(dp.frame(GPS_NMEA, b'$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47'))
    pump(time.time() + secs)
else:
    # Phone-rate flood: ~40 sentences/s, mix like the Poco's MTK GNSS (GGA/RMC/GSA/GSV/VTG + $PMTK proprietary up to 147 B)
    def cs(body): 
        c = 0
        for b in body.encode(): c ^= b
        return f'${body}*{c:02X}'
    t0 = time.time(); n = 0; tick = 0
    while time.time() - t0 < secs:
        tick += 1
        secs_utc = 123519 + tick
        batch = [cs(f'GPGGA,{secs_utc},4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,'),
                 cs(f'GPRMC,{secs_utc},A,4807.038,N,01131.000,E,000.5,054.7,030926,020.3,E'),
                 cs('GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1'), cs('GPVTG,054.7,T,034.4,M,005.5,N,010.2,K')]
        for i in range(1, 9):
            batch.append(cs(f'GPGSV,8,{i},32,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45'))
            batch.append(cs(f'GLGSV,8,{i},32,65,40,083,46,66,17,308,41,67,07,344,39,68,22,228,45'))
            batch.append(cs(f'GAGSV,8,{i},32,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45'))
            batch.append(cs(f'BDGSV,8,{i},32,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45'))
        batch.append(cs('PMTKRTK1,0,-522,-259200,0,0,0.000,0000,0.0000000,0.0000000,0.000,0.000,0.000,0.000,0.000,0.000,0.000,0.000,0.000,0.000,0,0,0,0,99,0,0,0,0,4,0'))
        batch.append(cs('PMTKNMA,0')); batch.append(cs('PMTKAGC,6'))
        for sent in batch:
            s.write(dp.frame(GPS_NMEA, sent.encode())); n += 1
            pump(time.time() + 1.0 / 40)
        pump(t0 + tick * 1.0)
    log.append(f'flood: sent {n} sentences in {secs:.0f}s ({n/secs:.0f}/s)')
    pump(time.time() + 2)
s.close()
png = os.path.join(out, f'screen_{mode}.png'); scr.png(png)
print('frames by type:', counts)
print('first STATS:', first_stats); print('last  STATS:', last_stats)
print('framing resyncs:', fr.bad)
for l in log: print(l)
print('PNG:', png)
