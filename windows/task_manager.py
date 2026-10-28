"""Process snapshots and checked termination without launching Taskmgr.exe."""
import ctypes
from ctypes import wintypes as w
import os
from pathlib import Path
import threading
import time


class NativeProcesses:
    def __init__(self,protected=()):
        self.protected=[Path(p).resolve() for p in protected]
        self.kernel=ctypes.WinDLL("kernel32",use_last_error=True);self.psapi=ctypes.WinDLL("psapi",use_last_error=True)
        self.kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD];self.kernel.OpenProcess.restype=w.HANDLE
        self.kernel.CloseHandle.argtypes=[w.HANDLE]
        self.kernel.QueryFullProcessImageNameW.argtypes=[w.HANDLE,w.DWORD,w.LPWSTR,ctypes.POINTER(w.DWORD)]
        self.kernel.GetProcessTimes.argtypes=[w.HANDLE,*([ctypes.POINTER(w.FILETIME)]*4)]
        self.kernel.TerminateProcess.argtypes=[w.HANDLE,w.UINT]
        if hasattr(self.kernel,"IsProcessCritical"):self.kernel.IsProcessCritical.argtypes=[w.HANDLE,ctypes.POINTER(w.BOOL)]
        self.psapi.GetProcessMemoryInfo.argtypes=[w.HANDLE,ctypes.c_void_p,w.DWORD]
    @staticmethod
    def ticks(value):return (value.dwHighDateTime<<32)|value.dwLowDateTime
    def info(self,pid,name="",handle=None):
        if type(pid)is not int or not 0<pid<=0xffffffff:raise ValueError("进程编号无效")
        own=handle is None
        handle=handle or self.kernel.OpenProcess(0x410,False,pid) or self.kernel.OpenProcess(0x1000,False,pid)
        item=dict(pid=pid,name=name,path="",created="",memory=0,cpu_ticks=0,critical=True,can_terminate=False)
        if not handle:return item
        try:
            birth,exit_,kernel,user=w.FILETIME(),w.FILETIME(),w.FILETIME(),w.FILETIME()
            if self.kernel.GetProcessTimes(handle,ctypes.byref(birth),ctypes.byref(exit_),ctypes.byref(kernel),ctypes.byref(user)):
                item["created"]=str(self.ticks(birth));item["cpu_ticks"]=self.ticks(kernel)+self.ticks(user)
            text=ctypes.create_unicode_buffer(32768);size=w.DWORD(len(text))
            if self.kernel.QueryFullProcessImageNameW(handle,0,text,ctypes.byref(size)):
                item["path"]=text.value;item["name"]=Path(text.value).name
            class Memory(ctypes.Structure):
                _fields_=[("cb",w.DWORD),("faults",w.DWORD)]+[(v,ctypes.c_size_t) for v in ("peak","working","ppeak","paged","npeak","nonpaged","pagefile","peakpagefile","private")]
            mem=Memory();mem.cb=ctypes.sizeof(mem)
            if self.psapi.GetProcessMemoryInfo(handle,ctypes.byref(mem),mem.cb):item["memory"]=mem.working
            critical=w.BOOL()
            if hasattr(self.kernel,"IsProcessCritical") and self.kernel.IsProcessCritical(handle,ctypes.byref(critical)):
                item["critical"]=bool(critical.value)
            try:resolved=Path(item["path"]).resolve() if item["path"] else None
            except (OSError,RuntimeError):resolved=None
            ours=resolved is not None and any(resolved==p or p in resolved.parents for p in self.protected)
            item["can_terminate"]=bool(item["created"]) and resolved is not None and pid not in (0,4,os.getpid()) and not item["critical"] and not ours
            return item
        finally:
            if own:self.kernel.CloseHandle(handle)
    def list(self,name=None):
        class Entry(ctypes.Structure):
            _fields_=[("dwSize",w.DWORD),("usage",w.DWORD),("pid",w.DWORD),("heap",ctypes.c_size_t),("module",w.DWORD),("threads",w.DWORD),("parent",w.DWORD),("priority",w.LONG),("flags",w.DWORD),("exe",w.WCHAR*260)]
        self.kernel.CreateToolhelp32Snapshot.argtypes=[w.DWORD,w.DWORD];self.kernel.CreateToolhelp32Snapshot.restype=w.HANDLE
        self.kernel.Process32FirstW.argtypes=[w.HANDLE,ctypes.POINTER(Entry)];self.kernel.Process32NextW.argtypes=[w.HANDLE,ctypes.POINTER(Entry)]
        snapshot=self.kernel.CreateToolhelp32Snapshot(2,0)
        if snapshot==ctypes.c_void_p(-1).value:raise ctypes.WinError(ctypes.get_last_error())
        items=[]
        try:
            entry=Entry();entry.dwSize=ctypes.sizeof(entry)
            more=self.kernel.Process32FirstW(snapshot,ctypes.byref(entry))
            while more and len(items)<2000:
                if entry.pid and (name is None or entry.exe.casefold()==name.casefold()):items.append(dict(self.info(entry.pid,entry.exe),parent=entry.parent,threads=entry.threads))
                more=self.kernel.Process32NextW(snapshot,ctypes.byref(entry))
        finally:self.kernel.CloseHandle(snapshot)
        return items
    def terminate(self,pid,created):
        if type(pid)is not int or not 0<pid<=0xffffffff:raise ValueError("进程编号无效")
        handle=self.kernel.OpenProcess(0x1001,False,pid)
        if not handle:raise ctypes.WinError(ctypes.get_last_error())
        try:
            item=self.info(pid,handle=handle)
            if not item["can_terminate"] or item["created"]!=created:raise ValueError("进程已变化、属于关键进程或 LanCmd 自身，未结束")
            if not self.kernel.TerminateProcess(handle,1):raise ctypes.WinError(ctypes.get_last_error())
        finally:self.kernel.CloseHandle(handle)


class TaskManager:
    def __init__(self,protected=(),backend=None):self.protected=protected;self.backend=backend;self.previous={};self.lock=threading.RLock()
    def request(self,q,error):
        try:
            if self.backend is None:
                if os.name!="nt":raise ValueError("任务管理器仅支持 Windows")
                self.backend=NativeProcesses(self.protected)
            action=q.get("action","list")
            if action=="list":
                with self.lock:
                    now=time.monotonic();items=self.backend.list();history={}
                    for item in items:
                        key=(item["pid"],item["created"]);previous=self.previous.get(key)
                        item["cpu"]=None
                        if previous and now>previous[0]:
                            item["cpu"]=min(100.0,max(0.0,(item["cpu_ticks"]-previous[1])/(now-previous[0])/100000/(os.cpu_count() or 1)))
                        history[key]=(now,item["cpu_ticks"])
                    self.previous=history
                return {"items":items,"self_pid":os.getpid(),"count":len(items)}
            pid=q.get("pid")
            if type(pid)is not int or not 0<pid<=0xffffffff:raise ValueError("进程编号无效")
            if action=="detail":return {"process":self.backend.info(pid)}
            if action!="terminate":raise ValueError("无效的任务操作")
            created=q.get("created")
            if not isinstance(created,str) or not 1<=len(created)<=20 or not created.isdigit() or pid in (4,os.getpid()):raise ValueError("请先刷新并选择可结束的进程")
            self.backend.terminate(pid,created);return {"terminated":True}
        except (OSError,ValueError,TypeError) as exc:raise error(str(exc))
