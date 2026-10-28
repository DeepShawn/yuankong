"""Upgrade admission, same-volume workspaces, process lifetime and private CMDs."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'windows'))
import local_upgrade as upgrade
import test_local_upgrade as fixtures


class UpgradeRestartTest(unittest.TestCase):
    def setUp(self):
        self.fixture=fixtures.UpgradeTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.root=self.fixture.root/'installed';self.root.mkdir()
        runtime=self.root/'python';runtime.mkdir();(runtime/'pythonw.exe').write_bytes(b'old runtime')
        (self.root/'old.txt').write_text('user data')
        (self.root/'upgrade').mkdir();self.package=self.root/'upgrade/release.zip'
        shutil.copyfile(self.fixture.zip,self.package)
        self.stopped=mock.Mock();self.prepared=mock.Mock()
        self.manager=upgrade.LocalUpgrade(self.root,self.fixture.root/'appdata','3.3.0',stop=self.stopped,prepare=self.prepared)
        self.platform=SimpleNamespace(name='nt',getpid=os.getpid,fsync=os.fsync,replace=os.replace)
        self.q=dict(action='apply',name='release.zip',sha256=upgrade.digest(self.package),request_id='a'*32)
        self.plans=[]
    def spawn(self,command,**kwargs):
        plan=json.loads(Path(command[-1]).read_text());self.plans.append(plan)
        (Path(plan['work'])/'ready').touch()
        return SimpleNamespace(poll=lambda:None)
    def request(self):
        with mock.patch.object(upgrade,'os',self.platform),mock.patch.object(upgrade.subprocess,'Popen',side_effect=self.spawn),mock.patch.object(upgrade.threading,'Timer') as timer:
            result=self.manager.request(self.q,ValueError)
        return result,timer
    def test_workspace_is_on_install_volume_and_nothing_is_extracted_before_disconnect(self):
        with self.manager.operation('screen'),self.manager.operation('upgrade'):
            result,timer=self.request()
        self.assertTrue(result['restarting']);plan=self.plans[0];work=Path(plan['work'])
        self.assertEqual(self.root/'.upgrade',work.parent)
        self.assertFalse((work/'stage').exists())
        self.assertEqual(self.q['sha256'],upgrade.digest(work/'package.zip'))
        self.assertEqual('user data',(self.root/'old.txt').read_text())
        self.stopped.assert_not_called();self.prepared.assert_called_once()
        timer.assert_called_once();timer.return_value.start.assert_called_once()
        timer.call_args.args[1]()
        self.stopped.assert_called_once()
        self.assertEqual('disconnecting',json.loads((self.manager.state/'upgrade-status.json').read_text())['status'])
    def test_corrupt_package_never_stops_the_old_receiver_or_starts_a_helper(self):
        self.q['sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'变化'):self.request()
        self.stopped.assert_not_called();self.assertFalse(self.manager.draining);self.assertFalse(self.plans)
    def test_in_flight_file_writes_block_upgrade_but_shared_screen_does_not(self):
        with self.manager.operation('put'),self.assertRaisesRegex(ValueError,'操作正在进行'):self.request()
        self.assertFalse(self.manager.draining);self.assertFalse(self.plans)
    def test_verification_without_extraction_checks_payload_hashes(self):
        info=upgrade.unpack(self.package,None,'3.3.0')
        self.assertEqual('3.4.0',info['version'])
        (self.fixture.package/'start.bat').write_bytes(b'changed');self.fixture.archive()
        with self.assertRaises(ValueError):upgrade.unpack(self.fixture.zip,None,'3.3.0')
    def test_batch_launch_uses_one_quoted_expansion_and_only_its_own_hidden_cmd(self):
        (self.root/'start.bat').write_text('rem LANCMD_UPGRADE_BOOTSTRAP_V1')
        child=mock.Mock();child.poll.return_value=0
        with mock.patch.object(upgrade.subprocess,'Popen',return_value=child) as spawn:
            launch=upgrade.ReceiverStart(self.root,self.fixture.root,'3.4.0')
            try:
                command=spawn.call_args.args[0];args=spawn.call_args.kwargs
                self.assertIn('"%LANCMD_UPGRADE_BATCH%" --upgrade',command)
                self.assertNotIn(str(self.root),command);self.assertNotIn('call ',command.lower())
                self.assertEqual(str(self.root/'start.bat'),args['env']['LANCMD_UPGRADE_BATCH'])
                self.assertEqual(0x08000000,args['creationflags']);self.assertNotIn('shell',args)
                launch.stop();child.terminate.assert_not_called()
            finally:launch.close()


class UpgradeDisconnectTest(unittest.TestCase):
    def test_all_sockets_are_closed_before_lease_cleanup_and_process_exit(self):
        import lan_command_agent as agent
        events=[]
        def state(name):
            return SimpleNamespace(sock=SimpleNamespace(shutdown=lambda how:events.append(name+':shutdown'),close=lambda:events.append(name+':close')),
                                   close_transfers=lambda:events.append(name+':release'))
        states=[state('phone'),state('screen')]
        with mock.patch.object(agent,'STOP',threading.Event()),mock.patch.object(agent,'_upgrade_exit_ready',threading.Event()),mock.patch.object(agent,'_connections',states),mock.patch.object(agent,'_tcp_listener',None),mock.patch.object(agent._app_freeze,'close',side_effect=lambda:events.append('freeze:close')),mock.patch.object(agent,'cleanup_pid',side_effect=lambda:events.append('pid:cleanup')),mock.patch.object(agent.os,'_exit',side_effect=lambda code:events.append('exit')):
            agent.upgrade_stop()
        self.assertEqual(['phone:shutdown','phone:close','screen:shutdown','screen:close','phone:release','screen:release','freeze:close','pid:cleanup','exit'],events)
    def test_cleanup_never_removes_a_new_instances_pid_file(self):
        import lan_command_agent as agent
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'agent.pid';path.write_text(str(os.getpid()+1))
            with mock.patch.object(agent,'PID_PATH',str(path)):
                agent.cleanup_pid();self.assertTrue(path.exists())
                path.write_text(str(os.getpid()));agent.cleanup_pid();self.assertFalse(path.exists())


if __name__=='__main__':unittest.main()
