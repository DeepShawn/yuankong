"""HTTP(S) links opened in the selected installed desktop browser."""
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit


def browsers():
    import winreg as w
    names={"msedge.exe":"Microsoft Edge","chrome.exe":"Google Chrome","firefox.exe":"Firefox","brave.exe":"Brave","opera.exe":"Opera"}
    out=[];seen=set()
    for exe,label in names.items():
        for hive in (w.HKEY_CURRENT_USER,w.HKEY_LOCAL_MACHINE):
            for view in (w.KEY_WOW64_64KEY,w.KEY_WOW64_32KEY):
                try:
                    with w.OpenKey(hive,"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\"+exe,0,w.KEY_READ|view) as key:
                        value=w.QueryValueEx(key,"")[0]
                    if not isinstance(value,str):continue
                    value=value.strip('"')
                    path=Path(os.path.expandvars(value)).resolve()
                    if path.is_file() and path.name.lower()==exe and str(path).casefold() not in seen:
                        seen.add(str(path).casefold());out.append(dict(id=str(path),name=label,path=str(path)))
                except (OSError,ValueError,RuntimeError):pass
    return out


def browser_request(q,error,inventory=None):
    try:
        if os.name!="nt" and inventory is None:raise ValueError("电脑浏览器控制仅支持 Windows")
        inventory=inventory or browsers;action=q.get("action","list")
        if action=="list":return {"items":inventory()}
        if action!="open":raise ValueError("无效的浏览器操作")
        url=q.get("url")
        if not isinstance(url,str) or len(url)>8192 or any(c.isspace() or ord(c)<33 or ord(c)==127 or c in '\\"<>' for c in url):raise ValueError("请输入有效网址，不能包含空格、控制字符或未编码的引号")
        parts=urlsplit(url)
        if parts.scheme.lower() not in ("http","https") or not parts.hostname or parts.username is not None or parts.password is not None or parts.port==0:raise ValueError("仅支持不含用户名密码的 HTTP / HTTPS 网址")
        selected=q.get("browser","default")
        if selected=="default":os.startfile(url,"open")
        else:
            found=next((i for i in inventory() if i["id"]==selected),None)
            if not found:raise ValueError("浏览器已卸载或路径改变，请刷新")
            subprocess.Popen([found["path"],url],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=0x08000000,close_fds=True)
        return {"opened":url}
    except (OSError,ValueError,StopIteration) as exc:raise error(str(exc))
