import struct
from dataclasses import dataclass


@dataclass(frozen=True)
class Tagged:
    tag: int
    value: object


def encode(value) -> bytes:
    out = bytearray()
    _write(out, value)
    return bytes(out)


def decode(data: bytes):
    reader = _Reader(bytes(data))
    value = reader.read()
    if reader.position != len(reader.data):
        raise ValueError("Trailing bytes after CBOR item")
    return value


def _write(out: bytearray, value):
    if value is None:
        out.append(0xf6)
    elif isinstance(value, bool):
        out.append(0xf5 if value else 0xf4)
    elif isinstance(value, int):
        if value >= 0:
            _head(out, 0, value)
        else:
            _head(out, 1, -1 - value)
    elif isinstance(value, (bytes, bytearray)):
        _head(out, 2, len(value))
        out.extend(value)
    elif isinstance(value, str):
        data = value.encode("utf-8")
        _head(out, 3, len(data))
        out.extend(data)
    elif isinstance(value, (list, tuple)):
        _head(out, 4, len(value))
        for item in value:
            _write(out, item)
    elif isinstance(value, dict):
        pairs = sorted((encode(key), encode(item)) for key, item in value.items())
        _head(out, 5, len(pairs))
        for key, item in pairs:
            out.extend(key)
            out.extend(item)
    elif isinstance(value, Tagged):
        _head(out, 6, value.tag)
        _write(out, value.value)
    else:
        raise ValueError("Cannot encode " + type(value).__name__)


def _head(out: bytearray, major: int, argument: int):
    kind = major << 5
    if argument < 24:
        out.append(kind | argument)
    elif argument < 0x100:
        out.append(kind | 24)
        out.append(argument)
    elif argument < 0x10000:
        out.append(kind | 25)
        out.extend(struct.pack(">H", argument))
    elif argument < 0x100000000:
        out.append(kind | 26)
        out.extend(struct.pack(">I", argument))
    else:
        out.append(kind | 27)
        out.extend(struct.pack(">Q", argument))


class _Reader:
    MAX_DEPTH = 32

    def __init__(self, data: bytes):
        self.data = data
        self.position = 0
        self.depth = 0

    def read(self):
        self.depth += 1
        if self.depth > self.MAX_DEPTH:
            raise ValueError("CBOR nested too deeply")
        initial = self._next()
        major = initial >> 5
        info = initial & 0x1f
        if major == 0:
            value = self._argument(info)
        elif major == 1:
            value = -1 - self._argument(info)
        elif major == 2:
            value = self._bytes(self._length(info))
        elif major == 3:
            value = self._bytes(self._length(info)).decode("utf-8")
        elif major == 4:
            value = [self.read() for _ in range(self._length(info))]
        elif major == 5:
            value = {}
            for _ in range(self._length(info)):
                key = self.read()
                if isinstance(key, (bytes, list, dict, Tagged)) or key in value:
                    raise ValueError("Duplicate or unsupported CBOR map key")
                value[key] = self.read()
        elif major == 6:
            value = Tagged(self._argument(info), self.read())
        elif info == 20:
            value = False
        elif info == 21:
            value = True
        elif info == 22:
            value = None
        else:
            raise ValueError("Unsupported CBOR simple value %d" % info)
        self.depth -= 1
        return value

    def _next(self) -> int:
        if self.position >= len(self.data):
            raise ValueError("Truncated CBOR")
        value = self.data[self.position]
        self.position += 1
        return value

    def _argument(self, info: int) -> int:
        if info < 24:
            return info
        length = {24: 1, 25: 2, 26: 4, 27: 8}.get(info)
        if length is None:
            raise ValueError("Indefinite or reserved CBOR length")
        value = 0
        for _ in range(length):
            value = (value << 8) | self._next()
        if value >= 1 << 63:
            raise ValueError("CBOR integer out of range")
        return value

    def _length(self, info: int) -> int:
        length = self._argument(info)
        if length > len(self.data) - self.position:
            raise ValueError("Truncated CBOR")
        return length

    def _bytes(self, length: int) -> bytes:
        value = self.data[self.position:self.position + length]
        self.position += length
        return value
