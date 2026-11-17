import pathlib,shutil
root=pathlib.Path(__file__).resolve().parent
for name in ('classes','dex'):
 p=(root/'build'/name).resolve()
 if p.parent != root/'build':raise RuntimeError('Unexpected build path')
 if p.exists():shutil.rmtree(p)
 p.mkdir(parents=True)
