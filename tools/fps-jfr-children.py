import sys, collections, importlib.util
spec=importlib.util.spec_from_file_location("j",__import__("os").path.join(__import__("os").path.dirname(__file__), "fps-jfr.py")); j=importlib.util.module_from_spec(spec); spec.loader.exec_module(j)
path, anchor = sys.argv[1], sys.argv[2]
depth = int(sys.argv[3]) if len(sys.argv)>3 else 1
ev=[e for e in j.load(path,"jdk.ExecutionSample") if j.thread_name(e)=="Render thread"]
c=collections.Counter(); n=len(ev)
for e in ev:
    fr=j.frames(e)  # innermost first
    idx=[i for i,f in enumerate(fr) if anchor in f]
    if not idx: continue
    i=idx[-1]  # outermost occurrence
    inner=[f for f in fr[:i] if f.startswith("com.killer560.") and "$$Lambda" not in f and "FeatureGuard" not in f]
    key=" > ".join(list(reversed(inner))[:depth]) if inner else "(vanilla/self) "+fr[max(i-1,0)]
    c[key]+=1
for k,v in c.most_common(40): print(f"{v:5d} {100*v/n:5.1f}%  {k}")
