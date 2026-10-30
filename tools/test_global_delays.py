"""Windows-owned global defaults, queue cancellation and source identity."""
import json
import os
from pathlib import Path
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'windows'))
from global_delays import GlobalDelays,category
class Error(Exception):pass
class DelayTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);self.calls=[]
        self.manager=GlobalDelays(self.root/'settings.json',lambda cmd,q,state:self.calls.append((cmd,q,state.client_id)) or {'value':42})
        self.addCleanup(self.manager.close);self.state=SimpleNamespace(client_id='phone',peer='127.0.0.1')
    def request(self,**q):return self.manager.request(q,self.state,Error)
    def intercept(self,cmd,**q):return self.manager.intercept(cmd,q,self.state,Error)
    def configure(self,c=1,o=1):self.request(action='configure',command=c,operation=o)
    def wait(self,ident):self.assertTrue(self.manager.jobs[ident]['done'].wait(4));return self.request(action='get',job_id=ident)['job']
    def test_command_and_operation_defaults_are_independent_and_persist_per_phone(self):
        self.configure(1,0)
        self.assertEqual({'command':1,'operation':0},self.request(action='settings')['settings'])
        self.assertIsNone(self.intercept('files',action='open'))
        job=self.intercept('exec',line='echo test')['job'];self.assertEqual(1,job['remaining']);self.assertEqual([],self.calls)
        result=self.wait(job['id']);self.assertEqual('completed',result['status']);self.assertEqual(42,result['result']['value'])
        restarted=GlobalDelays(self.root/'settings.json',lambda *args:{});self.addCleanup(restarted.close)
        self.assertEqual(1,restarted.request({'action':'settings'},self.state,Error)['settings']['command'])
        self.assertEqual(0,restarted.request({'action':'settings'},SimpleNamespace(client_id='other'),Error)['settings']['command'])
    def test_queries_streaming_stop_recovery_and_single_item_schedules_never_stack(self):
        self.configure(600,600)
        for cmd,q in [('screen',{}),('touch',{'action':'frame'}),('input',{}),('audio',{'action':'set'}),('upload',{'action':'begin'}),('download',{}),('file_schedule',{'action':'add'}),('delays',{'action':'cancel'}),('network',{'action':'unlock'}),('recordings',{'action':'stop'}),('announcements',{'action':'pause'}),('app_freeze',{'action':'submit','operation':'resume'}),('app_locks',{'action':'unlock'}),('file_locks',{'action':'unlock'}),('ls',{})]:
            with self.subTest(cmd=cmd):self.assertIsNone(self.intercept(cmd,**q))
        self.assertIsNone(self.intercept('exec',line='now',delay_override=0))
        self.assertIsNone(self.intercept('commands',action='add',items=[{'line':'x','delay':3}]))
        job=self.intercept('message',text='message',delay_override=1)['job'];self.assertEqual(1,job['remaining']);self.wait(job['id'])
    def test_cancel_owned_task_and_connection_object_changes_do_not_redirect_execution(self):
        self.configure();ident=self.intercept('exec',line='command')['job']['id']
        with self.assertRaises(Error):self.manager.request(dict(action='cancel',job_id=ident),SimpleNamespace(client_id='other'),Error)
        self.request(action='cancel',job_id=ident);self.assertEqual('cancelled',self.wait(ident)['status']);self.assertEqual([],self.calls)
        ident=self.intercept('exec',line='second')['job']['id'];self.state.client_id='other'
        self.assertTrue(self.manager.jobs[ident]['done'].wait(3));self.assertEqual('phone',self.calls[0][2])
    def test_pending_payloads_and_credentials_are_not_persisted_or_exposed(self):
        self.configure();ident=self.intercept('file_locks',action='lock',password='not a real password')['job']['id']
        self.assertNotIn('not a real password',json.dumps(self.request()))
        self.assertNotIn('not a real password',(self.root/'settings.json').read_text())
        self.request(action='cancel',job_id=ident);self.wait(ident);self.assertEqual({},self.manager.jobs[ident]['params'])
    def test_file_replacement_during_delay_is_refused_and_device_disconnect_cancels(self):
        self.configure();path=self.root/'test.txt';path.write_text('one')
        ident=self.intercept('rm',path=str(path))['job']['id'];path.write_text('changed')
        self.assertEqual('failed',self.wait(ident)['status']);self.assertEqual([],self.calls)
        ident=self.intercept('rm',path=str(path))['job']['id'];self.manager.stop_for_device({'paths':[str(self.root)]})
        self.assertEqual('cancelled',self.wait(ident)['status']);self.assertTrue(path.exists())
    def test_bad_settings_do_not_break_startup_and_can_be_repaired_with_a_backup(self):
        self.root.joinpath('settings.json').write_text('{invalid')
        restarted=GlobalDelays(self.root/'settings.json',lambda *args:{});self.addCleanup(restarted.close)
        self.assertTrue(restarted.request({'action':'settings'},self.state,Error)['warning'])
        with self.assertRaises(Error):restarted.intercept('exec',{'line':'x'},self.state,Error)
        restarted.request({'action':'configure','command':0,'operation':0},self.state,Error)
        self.assertTrue(list(self.root.glob('*.corrupt-*.json')))
        self.assertIsNone(restarted.intercept('exec',{'line':'x'},self.state,Error))
    def test_invalid_values_do_not_schedule_or_change_settings(self):
        for value in (-1,86401,True,'5'):
            with self.assertRaises(Error):self.request(action='configure',command=value,operation=0)
            with self.assertRaises(Error):self.intercept('exec',delay_override=value)
        self.assertFalse(self.manager.busy())
if __name__=='__main__':unittest.main()
