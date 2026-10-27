"""Filename/path index. Scans metadata only; a failed rebuild preserves the old index."""
import atexit
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import stat
import threading
import time
import uuid
from file_jobs import checked_path, file_token

MAX_ENTRIES=2_000_000
RESERVE=1_000_000_000
APPLICATION_ID=0x4C434653


def fixed_drives():
    if os.name!="nt":return []
    import ctypes
    from ctypes import wintypes as w
    kernel=ctypes.WinDLL("kernel32")
    kernel.GetLogicalDrives.restype=w.DWORD
    kernel.GetDriveTypeW.argtypes=[w.LPCWSTR];kernel.GetDriveTypeW.restype=w.UINT
    mask=kernel.GetLogicalDrives()
    return [chr(65+i)+":\\" for i in range(26) if mask&(1<<i) and kernel.GetDriveTypeW(chr(65+i)+":\\")==3]


class SearchCancelled(Exception):pass


class FileSearch:
    def __init__(self,directory,allow_non_windows=False):
        self.directory=Path(directory);self.database=self.directory/"files.sqlite"
        self.allow_non_windows=allow_non_windows;self.lock=threading.RLock();self.job=None;self.closed=False
        atexit.register(self.close)

    def _connect(self):
        connection=sqlite3.connect(self.database.as_uri()+"?mode=ro",uri=True,timeout=3)
        if connection.execute("PRAGMA application_id").fetchone()[0]!=APPLICATION_ID or connection.execute("PRAGMA user_version").fetchone()[0]!=1:
            connection.close();raise ValueError("索引版本无效，请重新建立")
        return connection

    def _metadata(self):
        if not self.database.is_file():return {"available":False,"count":0}
        connection=self._connect()
        try:return dict(json.loads(connection.execute("SELECT value FROM metadata").fetchone()[0]),available=True)
        finally:connection.close()

    def _public_job(self):return dict(self.job["data"]) if self.job else None

    @staticmethod
    def _roots(values):
        if not isinstance(values,list) or not 1<=len(values)<=32:raise ValueError("请选择 1–32 个本地磁盘或文件夹")
        roots=[]
        for value in values:
            path=checked_path(value,directory=True)
            if path not in roots:roots.append(path)
        return [p for p in roots if not any(other in p.parents for other in roots)]

    def request(self,q,state,error):
        try:
            if os.name!="nt" and not self.allow_non_windows:raise ValueError("文件索引仅支持 Windows")
            action=q.get("action","status")
            with self.lock:
                if action=="status":return {"index":self._metadata(),"job":self._public_job(),"drives":fixed_drives(),"can_cancel":bool(self.job and self.job["owner"]==state.client_id and not self.job["done"].is_set())}
                if action=="query":return self._query(q)
                if action=="cancel":
                    if not self.job or self.job["owner"]!=state.client_id or q.get("job_id")!=self.job["data"]["id"]:raise ValueError("索引任务不存在或属于其他手机")
                    self.job["cancel"].set();return {"cancel_requested":not self.job["done"].is_set()}
                if action!="build":raise ValueError("无效的文件搜索操作")
                if self.closed or self.job and not self.job["done"].is_set():raise ValueError("索引正在建立，请等待或取消")
                if not state.client_id:raise ValueError("连接身份无效")
                roots=self._roots(q.get("roots"))
                job=dict(owner=state.client_id,cancel=threading.Event(),done=threading.Event(),data=dict(id=uuid.uuid4().hex,status="building",phase="scanning",scanned=0,skipped=0,roots=[str(p) for p in roots],created=time.time(),current=""))
                self.job=job
                threading.Thread(target=self._build,args=(job,roots),daemon=True,name="file-search-index").start()
                return {"job":self._public_job()}
        except (OSError,ValueError,TypeError,sqlite3.Error) as exc:raise error(str(exc))

    def _enumerate(self,roots,job):
        stack=list(reversed(roots));count=0;skipped=0
        while stack:
            if job["cancel"].is_set():raise SearchCancelled()
            folder=stack.pop()
            if folder==self.directory or self.directory in folder.parents:continue
            try:
                with os.scandir(folder) as entries:
                    for entry in entries:
                        if job["cancel"].is_set():raise SearchCancelled()
                        if Path(entry.path)==self.directory:continue
                        try:
                            info=entry.stat(follow_symlinks=False)
                            if stat.S_ISLNK(info.st_mode) or getattr(info,"st_file_attributes",0)&0x400:skipped+=1;continue
                            directory=stat.S_ISDIR(info.st_mode)
                            if not directory and not stat.S_ISREG(info.st_mode):continue
                            if directory:stack.append(Path(entry.path))
                            count+=1
                            if count>MAX_ENTRIES:
                                with self.lock:job["data"]["incomplete"]=True
                                return
                            yield (entry.path,entry.name,str(folder),entry.name.casefold(),entry.path.casefold(),int(directory),0 if directory else info.st_size,info.st_mtime,file_token(info))
                            if count%64==0:
                                with self.lock:job["data"].update(scanned=count,skipped=skipped,current=entry.path)
                        except OSError:skipped+=1
            except OSError:skipped+=1
        with self.lock:job["data"].update(scanned=count,skipped=skipped)

    def _build(self,job,roots):
        stage=None;connection=None;low_space=[False]
        try:
            self.directory.mkdir(parents=True,exist_ok=True);checked_path(str(self.directory),directory=True)
            if shutil.disk_usage(self.directory).free<RESERVE:raise ValueError("索引磁盘至少需保留 1 GB 空间")
            stage=self.directory/("building-"+uuid.uuid4().hex+".sqlite")
            with stage.open("xb"):pass
            connection=sqlite3.connect(stage)
            connection.executescript("PRAGMA journal_mode=OFF;PRAGMA synchronous=OFF;PRAGMA application_id=%d;PRAGMA user_version=1;PRAGMA max_page_count=524288;CREATE TABLE files(path TEXT PRIMARY KEY COLLATE NOCASE,name TEXT,parent TEXT,n TEXT,p TEXT,dir INTEGER,size INTEGER,mtime REAL,file_id TEXT);CREATE TABLE metadata(value TEXT);"%APPLICATION_ID)
            disk_check=[0.0]
            def check():
                if job["cancel"].is_set():return 1
                now=time.monotonic()
                if now-disk_check[0]>.5:
                    disk_check[0]=now
                    if shutil.disk_usage(self.directory).free<RESERVE:low_space[0]=True;return 1
                return 0
            connection.set_progress_handler(check,5000)
            batch=[]
            for item in self._enumerate(roots,job):
                batch.append(item)
                if len(batch)>=512:
                    if check():
                        if low_space[0]:raise ValueError("索引空间不足，旧索引保留")
                        raise SearchCancelled()
                    connection.executemany("INSERT OR IGNORE INTO files VALUES(?,?,?,?,?,?,?,?,?)",batch);connection.commit();batch.clear()
            if batch:connection.executemany("INSERT OR IGNORE INTO files VALUES(?,?,?,?,?,?,?,?,?)",batch)
            with self.lock:job["data"]["phase"]="optimizing"
            connection.execute("CREATE INDEX by_name ON files(n,p)");connection.execute("CREATE INDEX by_path ON files(p)")
            fts=True
            try:connection.execute("CREATE VIRTUAL TABLE lookup USING fts5(n,p,content='files',content_rowid='rowid',tokenize='trigram')")
            except sqlite3.OperationalError:fts=False
            if fts:connection.execute("INSERT INTO lookup(lookup) VALUES('rebuild')")
            count=connection.execute("SELECT count(*) FROM files").fetchone()[0]
            metadata=dict(count=count,roots=[str(p) for p in roots],updated=time.time(),fts=fts,skipped=job["data"]["skipped"],incomplete=job["data"].get("incomplete",False))
            connection.execute("INSERT INTO metadata VALUES(?)",(json.dumps(metadata),));connection.commit();connection.close();connection=None
            with stage.open("r+b") as stream:os.fsync(stream.fileno())
            if job["cancel"].is_set():raise SearchCancelled()
            with self.lock:
                if job["cancel"].is_set():raise SearchCancelled()
                os.replace(stage,self.database);job["data"].update(status="completed",scanned=count,current="")
        except SearchCancelled:
            with self.lock:job["data"]["status"]="cancelled"
        except Exception as exc:
            with self.lock:job["data"].update(status="cancelled" if job["cancel"].is_set() else "failed",error="索引空间不足，旧索引保留" if low_space[0] else str(exc) or "索引未完成，旧索引保留")
        finally:
            if connection:connection.close()
            if stage:
                try:stage.unlink(missing_ok=True)
                except OSError:
                    with self.lock:job["data"]["cleanup_warning"]="临时索引清理失败："+str(stage)
            with self.lock:job["data"]["finished"]=time.time()
            job["done"].set()

    @staticmethod
    def _like(term):
        result="".join("%" if c=="*" else "_" if c=="?" else "\\"+c if c in "%_\\" else c for c in term)
        return result if "*" in term or "?" in term else "%"+result+"%"

    def _query(self,q):
        text=q.get("query","");scope=q.get("scope","name");kind=q.get("kind","all");offset=q.get("offset",0);limit=q.get("limit",100);sort=q.get("sort","name")
        if not isinstance(text,str) or len(text)>256 or "\0" in text or scope not in ("name","path") or kind not in ("all","files","folders") or type(offset) is not int or not 0<=offset<=2_000_000 or type(limit) is not int or not 1<=limit<=100 or sort not in ("name","path","modified","size"):
            raise ValueError("搜索条件无效；每页最多 100 项，关键字最多 256 字符")
        metadata=self._metadata()
        if not metadata["available"]:return {"items":[],"total":0,"offset":offset,"limit":limit,"index":metadata}
        connection=self._connect()
        try:
            deadline=time.monotonic()+3
            connection.set_progress_handler(lambda:int(time.monotonic()>deadline),10000)
            column="n" if scope=="name" else "p";where=[];params=[]
            terms=[(a or b).casefold() for a,b in re.findall(r'"([^"]+)"|(\S+)',text.strip())]
            if len(terms)>12:raise ValueError("最多输入 12 个关键词")
            for term in terms:
                where.append("f."+column+" LIKE ? ESCAPE '\\'");params.append(self._like(term))
                literal=max(re.split(r"[*?]",term),key=len)
                if metadata.get("fts") and len(literal)>=3:
                    where.append("f.rowid IN (SELECT rowid FROM lookup WHERE lookup MATCH ?)")
                    params.append(column+':"'+literal.replace('"','""')+'"')
            if kind!="all":where.append("f.dir=?");params.append(1 if kind=="folders" else 0)
            clause=" WHERE "+" AND ".join(where) if where else ""
            total=connection.execute("SELECT count(*) FROM files f"+clause,params).fetchone()[0]
            order={"name":"n,p","path":"p","modified":"mtime DESC,p","size":"size DESC,p"}[sort]
            rows=connection.execute("SELECT path,name,parent,dir,size,mtime,file_id FROM files f"+clause+" ORDER BY "+order+" LIMIT ? OFFSET ?",[*params,limit,offset]).fetchall()
            return {"items":[dict(path=p,name=n,parent=parent,dir=bool(d),size=s,mtime=m,file_id=token) for p,n,parent,d,s,m,token in rows],"total":total,"offset":offset,"limit":limit,"index":metadata}
        except sqlite3.OperationalError as exc:
            if "interrupted" in str(exc):raise ValueError("搜索范围较大，请增加关键词或指定文件夹后重建索引")
            raise
        finally:connection.close()

    def close(self):
        with self.lock:
            self.closed=True
            if self.job:self.job["cancel"].set()
