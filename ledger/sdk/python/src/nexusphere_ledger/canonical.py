import hashlib

_ESCAPES = {'"': '\\"', '\\': '\\\\', '\b': '\\b', '\f': '\\f', '\n': '\\n', '\r': '\\r', '\t': '\\t'}


def canonical_json(value) -> str:
    out = []
    _write(out, value)
    return "".join(out)


def canonical_bytes(value) -> bytes:
    return canonical_json(value).encode("utf-8")


def sha256_hex(data) -> str:
    if isinstance(data, str):
        data = data.encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def _write(out, value):
    if value is None:
        out.append("null")
    elif isinstance(value, bool):
        out.append("true" if value else "false")
    elif isinstance(value, int):
        out.append(str(value))
    elif isinstance(value, str):
        _write_string(out, value)
    elif isinstance(value, dict):
        for key in value:
            if not isinstance(key, str):
                raise ValueError("Canonical JSON keys must be strings")
        out.append("{")
        for index, key in enumerate(sorted(value, key=_java_order)):
            if index:
                out.append(",")
            _write_string(out, key)
            out.append(":")
            _write(out, value[key])
        out.append("}")
    elif isinstance(value, (list, tuple)):
        out.append("[")
        for index, item in enumerate(value):
            if index:
                out.append(",")
            _write(out, item)
        out.append("]")
    else:
        raise ValueError("Unsupported canonical JSON value: " + type(value).__name__)


def _java_order(key: str):
    return key.encode("utf-16-be")


def _write_string(out, text: str):
    out.append('"')
    for c in text:
        escaped = _ESCAPES.get(c)
        if escaped is not None:
            out.append(escaped)
        elif ord(c) < 0x20:
            out.append("\\u%04x" % ord(c))
        else:
            out.append(c)
    out.append('"')
