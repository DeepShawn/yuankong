"""Bounded read-only filesystem queries: a busy USB must not occupy the RPC pipe.

Only one daemon performs a given in-flight read. Timed-out Windows calls cannot
be cancelled safely; retries join the same call, rather than adding more threads.
"""
import threading
import time


class BoundedReads:
    def __init__(self, limit=8):
        self.lock=threading.Lock()
        self.pending={}
        self.limit=limit

    def read(self, key, loader, timeout=3):
        with self.lock:
            for old, entry in list(self.pending.items()):
                if entry['done'].is_set() and old!=key:
                    self.pending.pop(old,None)
            job=self.pending.get(key)
            if job is None:
                if len(self.pending)>=self.limit:
                    raise TimeoutError('磁盘仍在处理之前的读取，请稍后重试')
                job={'done':threading.Event()}
                self.pending[key]=job
                def run():
                    try:job['result']=loader()
                    except Exception as exc:job['error']=exc
                    finally:job['done'].set()
                threading.Thread(target=run,name='storage-read',daemon=True).start()
        if not job['done'].wait(timeout):
            raise TimeoutError('目标磁盘响应较慢，目录读取已停止等待；可重试，或在占位任务中取消创建后再读取')
        with self.lock:
            if self.pending.get(key) is job:self.pending.pop(key,None)
        if 'error' in job:raise job['error']
        return job['result']


class DriveUsage:
    def __init__(self, loader, ttl=2):
        self.loader,self.ttl=loader,ttl
        self.lock=threading.Lock()
        self.entries={}

    def snapshot(self, roots, budget=.25):
        now=time.monotonic()
        roots=list(roots)
        with self.lock:
            for root in roots:
                entry=self.entries.get(root)
                if entry is not None and (not entry['done'].is_set() or now-entry['checked']<self.ttl):continue
                job={'done':threading.Event(),'checked':now,'previous':entry.get('value') if entry else None}
                self.entries[root]=job
                def run(path=root,record=job):
                    try:
                        usage=self.loader(path)
                        record['value']={'path':path,'total':usage.total,'free':usage.free,'ready':True}
                    except OSError as exc:record['error']=str(exc)
                    finally:
                        record['checked']=time.monotonic();record['done'].set()
                threading.Thread(target=run,name='drive-usage',daemon=True).start()
            jobs=[(root,self.entries[root])for root in roots]
        deadline=time.monotonic()+budget
        result=[]
        for root,job in jobs:
            job['done'].wait(max(0,deadline-time.monotonic()))
            value=job.get('value') or job.get('previous')
            if value is not None:
                result.append(dict(value,pending=not job['done'].is_set(),stale='value' not in job))
            else:
                result.append({'path':root,'total':0,'free':0,'ready':False,'pending':not job['done'].is_set()})
        return result
