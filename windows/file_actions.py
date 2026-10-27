"""Explicit desktop opening, bounded previews, and ZIP extraction to a new folder."""
import base64
import codecs
import os
import math
from pathlib import Path
import shutil
import stat
import tempfile
import zipfile

PREVIEW_LIMIT = 128 * 1024
IMAGE_LIMIT = 2 * 1024 * 1024
EXTRACT_LIMIT = 512 * 1024 * 1024


def checked_path(value):
    if not isinstance(value, str) or not value.strip() or len(value) > 32767 or "\0" in value:
        raise ValueError("请提供有效文件路径")
    return Path(os.path.abspath(os.path.expanduser(value)))


def preview(path):
    if not path.is_file():
        raise ValueError("预览目标必须是文件")
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            items = archive.infolist()
            return {"kind": "archive", "items": [{"name": i.filename, "size": i.file_size, "dir": i.is_dir()}
                    for i in items[:500]], "count": len(items), "truncated": len(items) > 500}
    with path.open("rb") as stream:
        head = stream.read(32)
        image = (head.startswith((b"\xff\xd8\xff", b"\x89PNG\r\n\x1a\n", b"GIF87a", b"GIF89a", b"BM")) or
                 head.startswith(b"RIFF") and head[8:12] == b"WEBP")
        if image:
            stream.seek(0)
            data = stream.read(IMAGE_LIMIT + 1)
            if len(data) > IMAGE_LIMIT:
                raise ValueError("图片预览最多 2 MB，可下载到手机或在电脑打开")
            return {"kind": "image", "data": base64.b64encode(data).decode("ascii")}
        stream.seek(0)
        data = stream.read(PREVIEW_LIMIT + 4)
    truncated = path.stat().st_size > PREVIEW_LIMIT
    data = data[:PREVIEW_LIMIT]
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        encoding = "utf-16"
    elif b"\0" in data:
        raise ValueError("此文件不支持内置预览，可在电脑打开；支持文本、常见图片和 ZIP")
    else:
        encoding = "utf-8-sig"
    try:
        text = codecs.getincrementaldecoder(encoding)(errors="strict").decode(data, final=not truncated)
    except UnicodeError:
        try:
            text = codecs.getincrementaldecoder("gb18030")(errors="strict").decode(data, final=not truncated)
            encoding = "gb18030"
        except UnicodeError:
            raise ValueError("无法识别文本编码，可下载或在电脑打开")
    if any(ord(c) < 32 and c not in "\r\n\t\f" for c in text):
        raise ValueError("此二进制文件不支持内置预览")
    return {"kind": "text", "text": text, "encoding": encoding, "truncated": truncated}


def extract_zip(source, destination):
    if not source.is_file() or not zipfile.is_zipfile(source):
        raise ValueError("当前支持解压 ZIP 文件")
    if os.path.lexists(destination):
        raise ValueError("目标已存在，请选择一个新的文件夹名称；不会覆盖已有文件")
    if not destination.parent.is_dir():
        raise ValueError("目标父目录不存在")
    with zipfile.ZipFile(source) as archive:
        entries = archive.infolist()
        if len(entries) > 2000 or sum(i.file_size for i in entries) > EXTRACT_LIMIT:
            raise ValueError("ZIP 超过 2000 项或解压后超过 512 MB")
        seen, plan = {}, []
        for info in entries:
            name = info.filename.replace("\\", "/")
            parts = name.rstrip("/").split("/")
            if (not parts or name.startswith("/") or info.flag_bits & 1 or
                    stat.S_ISLNK(info.external_attr >> 16) or any(
                        part in ("", ".", "..") or part.endswith((".", " ")) or
                        any(ord(c) < 32 or c in ':*?"<>|' for c in part) or
                        part.split(".")[0].upper() in {"CON", "PRN", "AUX", "NUL", *["COM%d" % n for n in range(1, 10)],
                                                       *["LPT%d" % n for n in range(1, 10)]} for part in parts)):
                raise ValueError("ZIP 包含不安全路径、链接、加密项或不兼容 Windows 的文件名")
            key = "/".join(parts).casefold()
            if key in seen or any(seen.get("/".join(parts[:n]).casefold()) == "file" for n in range(1, len(parts))):
                raise ValueError("ZIP 中存在路径冲突或同名条目")
            if not info.is_dir() and any(k.startswith(key + "/") for k in seen):
                raise ValueError("ZIP 文件与文件夹路径冲突")
            seen[key] = "dir" if info.is_dir() else "file"
            plan.append((info, parts))
        stage = Path(tempfile.mkdtemp(prefix=".lancmd-extract-", dir=str(destination.parent)))
        total = 0
        try:
            for info, parts in plan:
                target = stage.joinpath(*parts)
                if info.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as input_file, target.open("xb") as output:
                    size = 0
                    while True:
                        chunk = input_file.read(65536)
                        if not chunk:
                            break
                        size += len(chunk); total += len(chunk)
                        if size > info.file_size or total > EXTRACT_LIMIT:
                            raise ValueError("解压内容超过声明大小或总量限制")
                        output.write(chunk)
            if os.path.lexists(destination):
                raise ValueError("目标目录已被创建，未覆盖")
            # Windows rename refuses an existing destination, including empty directories.
            os.rename(stage, destination)
        finally:
            if stage.exists():
                shutil.rmtree(stage)
    return {"path": str(destination), "count": len(plan), "bytes": total}


def file_request(q, error):
    try:
        path = checked_path(q.get("path"))
        action = q.get("action")
        if q.get("file_id") is not None:
            from file_jobs import file_token, snapshot
            if file_token(snapshot(path)) != q["file_id"]:raise ValueError("所选文件已变化，请刷新后重试")
        if action == "mtime":
            value=q.get("modified")
            if isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or not 0 <= value <= 4102444800:
                raise ValueError("修改日期须在 1970–2100 年之间")
            before=path.stat()
            os.utime(path,ns=(before.st_atime_ns,int(value*1_000_000_000)))
            return {"path":str(path),"mtime":path.stat().st_mtime}
        if action in ("text_read", "text_create", "text_write"):
            from text_editor import text_request
            return text_request(path, q)
        if action == "preview":
            return dict(preview(path), path=str(path))
        if action == "extract":
            destination = checked_path(q.get("destination") or str(path.with_name(path.stem + "_extracted")))
            return extract_zip(path, destination)
        if action == "open":
            from document_open import open_document
            return open_document(path,q.get("application","default"),q.get("background",False))
        raise ValueError("无效的文件操作")
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, NotImplementedError) as exc:
        raise error(str(exc))
