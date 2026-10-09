import re
from datetime import datetime, timedelta, timezone

_INSTANT = re.compile(r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$")


def parse_instant(text: str) -> datetime:
    if isinstance(text, datetime):
        return normalize(text)
    match = _INSTANT.match(text.strip() if isinstance(text, str) else "")
    if not match:
        raise ValueError("Not an ISO-8601 instant: " + repr(text))
    year, month, day, hour, minute, second, fraction, zone = match.groups()
    micros = int((fraction or "0")[:6].ljust(6, "0"))
    value = datetime(int(year), int(month), int(day), int(hour), int(minute), int(second), micros,
                     tzinfo=timezone.utc)
    if zone != "Z":
        sign = 1 if zone[0] == "+" else -1
        value -= sign * timedelta(hours=int(zone[1:3]), minutes=int(zone[4:6]))
    return value


def normalize(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise ValueError("An instant needs a time zone")
    return value.astimezone(timezone.utc)


def format_instant(value) -> str:
    value = parse_instant(value) if isinstance(value, str) else normalize(value)
    return "%04d-%02d-%02dT%02d:%02d:%02d.%06dZ" % (value.year, value.month, value.day, value.hour, value.minute,
                                                    value.second, value.microsecond)


def from_epoch_second(seconds: int) -> datetime:
    return datetime.fromtimestamp(seconds, tz=timezone.utc)


def epoch_text(seconds: int) -> str:
    return from_epoch_second(seconds).strftime("%Y-%m-%dT%H:%M:%SZ")
