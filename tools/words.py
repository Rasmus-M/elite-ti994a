"""Convert little-endian byte-pair variables to big-endian words.
usage: python tools/words.py var[,var...]   (variables at odd addresses are moved to word_variables)"""
import re,glob,collections,sys
N='\r\n'
files=[f.replace(chr(92),'/') for f in sorted(glob.glob('src/*.a99'))+sorted(glob.glob('src/rom-banks/rom-bank-*.a99'))]
lines={f:open(f,encoding='utf-8',newline='').read().split(N) for f in files}
sym=dict((k,int(v,16)) for k,v in re.findall(r'^(\w+):\s*\n\s*equ\s+>([0-9A-Fa-f]+)',open('symbols.txt').read(),re.M))
low={'bc':'c','de':'e','hl':'l','ix':'ixl','iy':'iyl','r0':'r0lb','r1':'r1lb','r2':'r2lb','b':'c','d':'e','h':'l'}
word={'b':'bc','d':'de','h':'hl'}
def code(l): return l.split(';')[0].rstrip()
def pair_at(f,i):
    ls=lines[f]
    if i+1>=len(ls): return None
    a,b=code(ls[i]).strip(),code(ls[i+1]).strip()
    for x,y,order in ((a,b,0),(b,a,1)):
        m=re.match(r'^movb @(\w+)\+1,(\w+)$',x); n=re.match(r'^movb @(\w+),@(\w+)$',y)
        if m and n and n.group(1)==m.group(1) and m.group(2) in low and low[m.group(2)]==n.group(2): return (m.group(1),'r',m.group(2),order)
        m=re.match(r'^movb (\w+),@(\w+)\+1$',x); n=re.match(r'^movb @(\w+),@(\w+)$',y)
        if m and n and n.group(2)==m.group(2) and m.group(1) in low and low[m.group(1)]==n.group(1): return (m.group(2),'w',m.group(1),order)
    return None
JUMPS=('jeq','jne','jgt','jlt','jl ','jh ','jhe','jle','joc','jnc','jno','jop')
def convert(names):
    names=list(names); moved=[]; report=[]
    for f in files:
        ls=lines[f]; out=[]; i=0
        while i<len(ls):
            p=pair_at(f,i) if i+1<len(ls) else None
            if p and p[0] in names:
                X,kind,reg,order=p
                reg=word.get(reg,reg)
                nxt=''
                for j in range(i+2,min(i+6,len(ls))):
                    c=code(ls[j]).strip()
                    if c and not c.startswith(('*',';')) and not re.match(r'^\w+:$',c) and c!='!': nxt=c; break
                if nxt.startswith(JUMPS): report.append('FLAGS? %s:%d %s then %s'%(f,i+1,X,nxt))
                ins='mov  @%s,%s'%(X,reg) if kind=='r' else 'mov  %s,@%s'%(reg,X)
                # keep the trailing columns of the line that has Z80 text
                tails=[l[l.index(';'):] if ';' in l else '' for l in (ls[i],ls[i+1])]
                def z(t):
                    q=t.split(';'); return q[1].strip() if len(q)>1 else ''
                tail=tails[0] if z(tails[0]) or not z(tails[1]) else tails[1]
                if not z(tails[0]) and not z(tails[1]):
                    # keep whichever has a comment
                    c0=tails[0].split(';',2)[2].strip() if tails[0].count(';')>=2 else ''
                    c1=tails[1].split(';',2)[2].strip() if tails[1].count(';')>=2 else ''
                    tail=tails[0] if c0 or not c1 else tails[1]
                out.append((('       '+ins).ljust(59)+tail).rstrip() if tail else '       '+ins)
                i+=2; continue
            l=ls[i]; c=code(l)
            if c and not c.startswith('*') and not re.match(r'^\w+:',c):
                rest=l[len(c):]
                for X in names:
                    if re.search(r'\b'+X+r'\b',c):
                        if re.search(r'(?<!@)\b'+X+r'\b',c) or re.search(r'@'+X+r'\b(\+(?!1\b)|\()',c) or re.search(r'@'+X+r'\+1\w',c):
                            report.append('OTHER REF %s:%d %s'%(f,i+1,c.strip()))
                        c2=re.sub(r'@'+X+r'\+1\b','@\x00',c)
                        c2=re.sub(r'@'+X+r'\b(?!\+)','@'+X+'+1',c2)
                        c2=c2.replace('@\x00','@'+X)
                        if c2!=c:
                            # keep the column layout
                            c=c2.ljust(len(l[:len(code(l))].ljust(59))) if rest else c2
                            l=(c2.ljust(59)+rest.lstrip()) if rest else c2
                            c=code(l)
            out.append(l); i+=1
        lines[f]=out
    # definitions
    f='src/main-code.a99'; ls=lines[f]
    for X in names:
        i=ls.index(next(l for l in ls if re.match(r'^'+X+r':',l)))
        j=i+1
        c=code(ls[j]).strip()
        m=re.match(r'^byte\s+>([0-9A-Fa-f]{2}),\s*>([0-9A-Fa-f]{2})$',c)
        if not m: report.append('DEFINITION %s: %s'%(X,c)); continue
        lo,hi=m.groups(); addr=re.search(r'; >(\w+)',ls[i]); addr=addr.group(1) if addr else ''
        if sym[X]&1:
            ls[i]='* (TI: %s is a word in word_variables; these two bytes are unused)'%X
            moved.append((X,hi+lo,addr))
        else:
            ls[j]='       data >%s%s'%(hi,lo)
    if moved:
        k=ls.index(next(l for l in ls if l.startswith('word_variables_end:')))
        new=[]
        for X,v,addr in moved:
            new.append((X+':').ljust(59)+('; >'+addr if addr else ''))
            new.append('       data >%s'%v)
        ls[k:k]=new
    for f in files: open(f,'w',encoding='utf-8',newline='').write(N.join(lines[f]))
    return report
if __name__=='__main__':
    for r in convert(sys.argv[1].split(',')): print(r)
