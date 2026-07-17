import sys
from pathlib import Path

# pony tail: add parent so src is importable as a package (agent.py uses relative imports)
sys.path.insert(0, str(Path(__file__).parent.parent))
