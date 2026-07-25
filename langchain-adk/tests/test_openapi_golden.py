"""Contract drift detection for the Java <-> Python ADK boundary.

The committed ``openapi.golden.json`` is the single source of truth for the
ADK HTTP surface consumed by ``LangChainAdkProvider`` on the Java side.
If this test fails, the FastAPI schema changed: either revert the breaking
change or regenerate the golden file AND update the Java-side stubs in the
same PR:

    .venv/Scripts/python -c "from src.server import app; import json; \
        open('openapi.golden.json','w',encoding='utf-8').write( \
        json.dumps(app.openapi(), indent=2, sort_keys=True))"
"""

import json
from pathlib import Path

from src.server import app

GOLDEN_PATH = Path(__file__).resolve().parent.parent / "openapi.golden.json"


def _canonical(schema: dict) -> str:
    return json.dumps(schema, indent=2, sort_keys=True)


def test_openapi_schema_matches_committed_golden_file():
    golden = GOLDEN_PATH.read_text(encoding="utf-8")
    current = _canonical(app.openapi())
    assert current == golden, (
        "FastAPI OpenAPI schema drifted from openapi.golden.json. "
        "If the change is intentional, regenerate the golden file and "
        "update the Java-side ADK stubs in the same PR (see module docstring)."
    )


def test_golden_file_covers_core_adk_endpoints():
    """Guards against an accidentally truncated/empty golden file."""
    golden = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
    paths = golden.get("paths", {})
    for endpoint in ("/health", "/run"):
        assert endpoint in paths, f"Golden file is missing core endpoint {endpoint}"
