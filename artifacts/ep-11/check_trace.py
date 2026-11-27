import re,sys
# script: key held during ticks
events=[(6,'DOWN',1),(26,'DOWN',0),(31,'LEFT',1),(51,'LEFT',0),(56,'UP',1),(76,'UP',0),(81,'RIGHT',1),(101,'RIGHT',0)]
held={}
def keys_at(t):
    h=dict()
    for tick,k,p in events:
        if tick<=t: h[k]=p
    return {k for k,v in h.items() if v}
x,y,n,frame,dir_=200,200,0,0,'down'
bad=0
lines=open(sys.argv[1]).read().splitlines()
for i,l in enumerate(lines,1):
    m=re.match(r'tick=(\d+) x=(\d+) y=(\d+) camx=(-?\d+) camy=(-?\d+) dir=(\w+) frame=(\d)$',l)
    if not m: print('BAD FORMAT',l); bad+=1; continue
    t=int(m[1])
    k=keys_at(t)
    dx=(1 if 'RIGHT' in k else 0)-(1 if 'LEFT' in k else 0)
    dy=(1 if 'DOWN' in k else 0)-(1 if 'UP' in k else 0)
    if dx==0 and dy==0: n=0; frame=0
    else:
        n+=1; frame=((n-1)//8)%2
        if dx: dir_='right' if dx>0 else 'left'
        else: dir_='down' if dy>0 else 'up'
        x+=dx; y+=dy
    got=(int(m[2]),int(m[3]),m[6],int(m[7]))
    exp=(x,y,dir_,frame)
    if got!=exp: print('MISMATCH tick',t,'got',got,'exp',exp); bad+=1
    if int(m[4])!=x+8-80 or int(m[5])!=y+8-60: print('CAM?',t,m[4],m[5]); bad+=1
print('lines',len(lines),'mismatches',bad)
for t in [1,6,7,14,15,16,17,25,26,31,40,51,56,60,71,72,76,81,90,101,105]:
    print(lines[t-1])
