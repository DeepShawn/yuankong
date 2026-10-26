"""Bounded text editing with exclusive creation and optimistic conflict checks."""
import codecs
import ctypes
import hashlib
import os
from pathlib import Path
import re
import stat
import tempfile
import threading

LIMIT = 512 * 1024
ENCODINGS = {"utf-8": ("utf-8", b""), "utf-8-bom": ("utf-8", codecs.BOM_UTF8),
             "gbk": ("gbk", b""), "gb18030": ("gb18030", b""),
             "utf-16-le": ("utf-16-le", codecs.BOM_UTF16_LE), "utf-16-be": ("utf-16-be", codecs.BOM_UTF16_BE)}
_lock = threading.RLock()


def _raw(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
        raise ValueError("编辑目标必须是普通文件，不支持链接或重解析点")
    with path.open("rb") as stream:
        data = stream.read(LIMIT + 1)
        opened = os.fstat(stream.fileno())
    if len(data) > LIMIT:
        raise ValueError("在线编辑最多 512 KB，请下载到手机编辑")
    if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino):
        raise ValueError("文件在读取期间被替换，请重新加载")
    return data, info


def revision(data):
    return hashlib.sha256(data).hexdigest()


def _decode(data, encoding="auto"):
    if encoding == "auto":
        if data.startswith(codecs.BOM_UTF8): encoding = "utf-8-bom"
        elif data.startswith(codecs.BOM_UTF16_LE): encoding = "utf-16-le"
        elif data.startswith(codecs.BOM_UTF16_BE): encoding = "utf-16-be"
        else:
            try: data.decode("utf-8"); encoding = "utf-8"
            except UnicodeError: encoding = "gb18030"
    if encoding not in ENCODINGS:
        raise ValueError("不支持的文本编码")
    codec, bom = ENCODINGS[encoding]
    text = (data[len(bom):] if bom and data.startswith(bom) else data).decode(codec, "strict")
    if any(ord(c) < 32 and c not in "\r\n\t\f" for c in text):
        raise ValueError("文件包含二进制控制字符，不能作为文本编辑")
    endings = re.findall(r"\r\n|\r|\n", text)
    newline = "crlf" if not endings or endings.count("\r\n") >= max(endings.count("\n"), endings.count("\r")) else ("lf" if endings.count("\n") >= endings.count("\r") else "cr")
    return text, encoding, newline, len(set(endings)) > 1


def _encode(text, encoding, newline):
    if not isinstance(text, str) or len(text) > LIMIT or "\0" in text:
        raise ValueError("文本过大或包含空字符")
    if encoding not in ENCODINGS or newline not in ("crlf", "lf", "cr", "preserve"):
        raise ValueError("编码或换行格式无效")
    if newline != "preserve":
        text = re.sub(r"\r\n|\r|\n", {"crlf":"\r\n", "lf":"\n", "cr":"\r"}[newline], text)
    codec, bom = ENCODINGS[encoding]
    data = bom + text.encode(codec, "strict")
    if len(data) > LIMIT:
        raise ValueError("编码后的文件超过 512 KB")
    return data


def _name(path):
    name = path.name
    if (not name or name in (".", "..") or name.endswith((".", " ")) or
        any(ord(c) < 32 or c in ':*?"<>|' for c in name) or
        name.split(".")[0].upper() in {"CON", "PRN", "AUX", "NUL", *["COM%d" % n for n in range(1,10)], *["LPT%d" % n for n in range(1,10)]}):
        raise ValueError("文件名不兼容 Windows")
    if not path.parent.is_dir():
        raise ValueError("父目录不存在")


def read_text(path, encoding="auto"):
    data, info = _raw(path)
    text, encoding, newline, mixed = _decode(data, encoding)
    return dict(path=str(path), text=text, encoding=encoding, newline=newline, mixed=mixed,
                revision=revision(data), size=len(data), mtime_ns=info.st_mtime_ns)


def write_text(path, q, create=False):
    _name(path)
    data = _encode(q.get("text", ""), q.get("encoding", "utf-8"), q.get("newline", "crlf"))
    with _lock:
        if create:
            # Exclusive create protects against races and existing links.
            with path.open("xb") as stream:
                stream.write(data); stream.flush(); os.fsync(stream.fileno())
        else:
            expected = q.get("revision")
            if not isinstance(expected, str) or not re.fullmatch("[0-9a-f]{64}", expected):
                raise ValueError("缺少文件版本，请先读取文件")
            original, info = _raw(path)
            if revision(original) != expected:
                raise ValueError("文件已被外部修改，未覆盖；请先另存草稿或重新加载")
            if data == original:
                return dict(path=str(path), revision=expected, size=len(data))
            fd, temp = tempfile.mkstemp(prefix=".lancmd-edit-", dir=str(path.parent))
            try:
                with os.fdopen(fd, "wb") as stream:
                    stream.write(data); stream.flush(); os.fsync(stream.fileno())
                current, now = _raw(path)
                if revision(current) != expected or (now.st_dev, now.st_ino, now.st_mtime_ns) != (info.st_dev, info.st_ino, info.st_mtime_ns):
                    raise ValueError("文件在保存期间被修改，未覆盖")
                if os.name == "nt":
                    from ctypes import wintypes as w
                    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
                    kernel.ReplaceFileW.argtypes = [w.LPCWSTR,w.LPCWSTR,w.LPCWSTR,w.DWORD,ctypes.c_void_p,ctypes.c_void_p]
                    kernel.ReplaceFileW.restype = w.BOOL
                    # ReplaceFile retains the target's DACL, creation time and ADS.
                    if not kernel.ReplaceFileW(str(path), temp, None, 0, None, None):
                        raise ctypes.WinError(ctypes.get_last_error())
                else:
                    os.chmod(temp, stat.S_IMODE(info.st_mode)); os.replace(temp, path)
            finally:
                try: os.unlink(temp)
                except FileNotFoundError: pass
    return dict(path=str(path), revision=revision(data), size=len(data))


def text_request(path, q):
    action = q.get("action")
    if action == "text_read": return read_text(path, q.get("encoding", "auto"))
    if action in ("text_create", "text_write"): return write_text(path, q, create=action == "text_create")
    raise ValueError("无效的文本操作")
