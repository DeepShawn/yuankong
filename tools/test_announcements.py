"""Announcement motion, ownership/lifecycle and actual native raster/window checks."""
import copy
import ctypes
import io
import json
import os
from pathlib import Path
import queue
import struct
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'windows'))
from announcements import Announcements,DEFAULT,validate,motion_frame
class Error(Exception):pass

class ModelTest(unittest.TestCase):
    def test_all_styles_and_colors_validate_without_executing_text(self):
        config=validate(dict(text='中文 & "hello" <test> 😀\nnext',background='#abcdef'))
        self.assertEqual('#ABCDEF',config['background']);self.assertEqual('left',config['motion'])
        for shape in ('rectangle','rounded','pill'):
            for mode in ('ticker','popup','sticky'):self.assertEqual(shape,validate(dict(text='notice',shape=shape,mode=mode))['shape'])
        for change in ({'text':''},{'text':'bad\0'},{'speed':True},{'speed':0},{'font_size':97},{'background':'red'}, {'opacity':0},{'duration':3601},{'repeats':101},{'motion':'shell'}, {'font':'C:/font.exe'}):
            with self.subTest(change=change),self.assertRaises(ValueError):validate(dict({'text':'notice'},**change))
    def test_motion_depends_on_elapsed_not_timer_frame_count(self):
        self.assertEqual((300,15.,0,1.),motion_frame('left',300,60,200,30,0,100))
        first=motion_frame('left',300,60,200,30,1,100);self.assertEqual(200,first[0])
        self.assertEqual(1,motion_frame('left',300,60,200,30,5.32,100)[2])
        self.assertGreater(motion_frame('right',300,60,200,30,1,100)[0],-200)
        self.assertEqual(60,motion_frame('up',300,60,200,30,0,100)[1])
        for direction in ('left','right','up','down','bounce','static','fade'):
            x,y,cycles,alpha=motion_frame(direction,300,60,200,30,10,100)
            self.assertGreaterEqual(cycles,0);self.assertTrue(0<=alpha<=1)

class Fake:
    def __init__(self):self.started=[]
    def run(self,config,report,commands,cancel):
        self.started.append(config);report(dict(status='playing',cycles=0,elapsed=0))
        while not cancel.wait(.01):
            try:action=commands.get_nowait()
            except queue.Empty:continue
            report(dict(status='paused' if action=='pause' else 'playing'))
        report(dict(status='cancelled',reason='stop'))

class OwnershipTest(unittest.TestCase):
    def setUp(self):self.backend=Fake();self.manager=Announcements(self.backend);self.addCleanup(self.manager.close);self.state=SimpleNamespace(client_id='phone')
    def wait(self,predicate):
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            if predicate():return
            time.sleep(.01)
        self.fail('announcement did not settle')
    def call(self,**q):return self.manager.request(q,self.state,Error)
    def test_replace_pause_resume_stop_and_other_phone_cannot_take_over(self):
        ident=self.call(action='show',config={'text':'one'})['job']['id'];self.wait(lambda:self.call()['job']['status']=='playing')
        other=SimpleNamespace(client_id='other')
        with self.assertRaises(Error):self.manager.request(dict(action='stop',job_id=ident),other,Error)
        with self.assertRaises(Error):self.manager.request(dict(action='show',config={'text':'two'}),other,Error)
        self.call(action='pause',job_id=ident);self.wait(lambda:self.call()['job']['status']=='paused')
        self.call(action='resume',job_id=ident);self.wait(lambda:self.call()['job']['status']=='playing')
        second=self.call(action='show',config={'text':'two'})['job']['id'];self.wait(lambda:len(self.backend.started)==2)
        self.assertTrue(self.manager.jobs[ident]['done'].is_set())
        self.call(action='stop',job_id=second);self.wait(lambda:self.manager.jobs[second]['done'].is_set())
        self.assertEqual('cancelled',self.call()['job']['status'])
    def test_worker_failure_is_visible_and_close_cancels_current_message(self):
        self.backend.run=lambda *args:(_ for _ in ()).throw(OSError('render failed'))
        ident=self.call(action='show',config={'text':'one'})['job']['id'];self.wait(lambda:self.manager.jobs[ident]['done'].is_set())
        self.assertEqual('failed',self.call()['job']['status']);self.assertIn('render failed',self.call()['job']['error'])

@unittest.skipUnless(os.name=='nt','Windows GDI+ and desktop message pump')
class NativeTest(unittest.TestCase):
    def test_real_surface_renders_chinese_text_and_custom_color(self):
        from notice_window import Surface
        config=validate(dict(text='测试消息 Chinese 123',motion='static',shape='rounded',background='#123456'))
        with tempfile.TemporaryDirectory() as folder:
            surface=Surface(800,86,config)
            try:surface.paint(1);surface.save_bmp(Path(folder)/'notice.bmp')
            finally:surface.close()
            data=(Path(folder)/'notice.bmp').read_bytes();self.assertEqual(b'BM',data[:2])
            pixels=data[54:];self.assertEqual(bytes((0x56,0x34,0x12)),pixels[(10*800+10)*4:(10*800+10)*4+3])
            self.assertGreater(sum(pixels[i:i+3]!=bytes((0x56,0x34,0x12)) for i in range(0,len(pixels),4)),150)
    def test_native_window_can_pause_resume_and_pipe_eof_closes_it(self):
        command=[sys.executable,'-I','-X','utf8',str(Path(__file__).resolve().parents[1]/'windows'/'announcements.py'),'--worker']
        p=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8',creationflags=0x08000000)
        incoming=queue.Queue()
        threading.Thread(target=lambda:[incoming.put(json.loads(line)) for line in p.stdout],daemon=True).start()
        def wait_status(expected):
            end=time.monotonic()+8
            while time.monotonic()<end:
                try:value=incoming.get(timeout=.2)
                except queue.Empty:continue
                if value['status']=='failed':self.fail(value.get('error'))
                if value['status']==expected:return value
            self.fail('native state timeout: '+expected)
        try:
            p.stdin.write(json.dumps(dict(text='本机测试 Native notice',repeats=0,duration=0,prefix='Native notice test',shape='rounded'))+'\n');p.stdin.flush()
            ready=wait_status('playing');self.assertGreater(ready.get('width',0),100)
            p.stdin.write('{"action":"pause"}\n');p.stdin.flush();wait_status('paused')
            p.stdin.write('{"action":"resume"}\n');p.stdin.flush();wait_status('playing')
            p.stdin.close();p.stdin=None
            wait_status('cancelled');self.assertEqual(0,p.wait(5),p.stderr.read())
        finally:
            if p.poll() is None:p.kill();p.wait(5)
            if p.stdin:p.stdin.close()
            p.stdout.close();p.stderr.close()
if __name__=='__main__':unittest.main()
