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
import time
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


class UpgradeCleanupTest(unittest.TestCase):
    def setUp(self):
        self.fixture=fixtures.UpgradeTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.root=self.fixture.root/'installed';self.root.mkdir();(self.root/'upgrade').mkdir()
        self.work=self.root/'.upgrade/run-test';self.work.mkdir(parents=True)
        (self.work/'backup').mkdir();(self.work/'backup/notes.txt').write_text('recovery')
        self.package=self.root/'upgrade/used.zip';shutil.copyfile(self.fixture.zip,self.package)
        shutil.copyfile(self.fixture.zip,self.work/'package.zip')
        self.state=self.fixture.root/'state';self.state.mkdir()
        self.manager=upgrade.LocalUpgrade(self.root,self.state,'3.4.0')
        self.report=dict(status='starting',version='3.4.0',pid=os.getpid(),request_id='a'*32,backup=str(self.work/'backup'))
    def save(self):upgrade.write_json(self.state/'upgrade-status.json',self.report)
    def test_only_verified_used_and_older_archives_are_removed_not_backups_future_or_damaged_files(self):
        future=self.root/'upgrade/future.zip'
        upgrade.build_manifest(self.fixture.package,'3.4.1');self.fixture.archive();shutil.copyfile(self.fixture.zip,future)
        damaged=self.root/'upgrade/damaged.zip'
        (self.fixture.package/'Start.exe').write_bytes(b'wrong content');self.fixture.archive();shutil.copyfile(self.fixture.zip,damaged)
        unrelated=self.root/'upgrade/photos.zip';unrelated.write_bytes(b'photos, not a release')
        result=upgrade.cleanup_packages(self.root,self.work,'3.4.0')
        self.assertEqual(2,result['deleted']);self.assertFalse(self.package.exists());self.assertFalse((self.work/'package.zip').exists())
        for path in (future,damaged,unrelated,self.work/'backup/notes.txt'):self.assertTrue(path.exists(),str(path))
    def test_cleanup_waits_for_completed_report_of_this_healthy_process(self):
        self.save();thread=self.manager.cleanup_after_start(self.work/'healthy.json',timeout=2)
        time.sleep(.05);self.assertTrue(self.package.exists())
        self.report['status']='completed';self.save();thread.join(3)
        self.assertFalse(thread.is_alive());self.assertFalse(self.package.exists())
        final=json.loads((self.state/'upgrade-status.json').read_text())
        self.assertEqual('completed',final['status']);self.assertEqual(2,final['cleanup']['deleted'])
    def test_failed_or_another_process_update_keeps_all_packages(self):
        for phase,pid in [('rolled_back',os.getpid()),('completed',os.getpid()+1)]:
            with self.subTest(phase=phase):
                self.report.update(status=phase,pid=pid);self.save()
                thread=self.manager.cleanup_after_start(self.work/'healthy.json',timeout=.1);thread.join(1)
                self.assertFalse(thread.is_alive());self.assertTrue(self.package.exists())
    def test_outside_workspace_and_changed_job_cannot_trigger_cleanup(self):
        self.assertIsNone(self.manager.cleanup_after_start(self.fixture.root/'healthy.json'))
        result=upgrade.cleanup_packages(self.root,self.work,'3.4.0',allowed=lambda:False)
        self.assertEqual(0,result['deleted']);self.assertTrue(self.package.exists())
    def test_files_replaced_during_validation_are_not_removed(self):
        if os.name=='nt':
            with upgrade.cleanup_archive(self.package):
                with self.assertRaises(OSError):os.replace(self.work/'package.zip',self.package)
        else:
            with upgrade.cleanup_archive(self.package) as (_,remove):
                os.replace(self.work/'package.zip',self.package)
                with self.assertRaisesRegex(ValueError,'已变化'):remove()
        self.assertTrue(self.package.exists())


@unittest.skipUnless(os.name=="nt","Windows exclusive listener rebinding")
class NativeUpgradeConnectionTest(unittest.TestCase):
    def test_disconnect_releases_exclusive_port_for_immediate_restart(self):
        import lan_command_agent as agent
        listener=agent.bind_listener(dict(bind="127.0.0.1",port=0),attempts=1)
        port=listener.getsockname()[1];clients=[];accepted=[];new_listener=None
        try:
            for _ in range(2):
                client=socket.create_connection(("127.0.0.1",port),timeout=2);clients.append(client)
                peer,_=listener.accept();accepted.append(peer)
            states=[SimpleNamespace(sock=peer,close_transfers=lambda:None) for peer in accepted]
            with mock.patch.object(agent,'STOP',threading.Event()),mock.patch.object(agent,'_upgrade_exit_ready',threading.Event()),mock.patch.object(agent,'_connections',states),mock.patch.object(agent,'_tcp_listener',listener),mock.patch.object(agent._app_freeze,'close'),mock.patch.object(agent,'cleanup_pid'),mock.patch.object(agent.os,'_exit'):
                agent.upgrade_stop()
            # Do not wait for the phones to close their sides or for TIME_WAIT.
            new_listener=agent.bind_listener(dict(bind="127.0.0.1",port=port),attempts=1)
            with socket.create_connection(("127.0.0.1",port),timeout=2) as fresh:
                peer,_=new_listener.accept()
                with peer:peer.sendall(b"ready")
                self.assertEqual(b"ready",fresh.recv(5))
        finally:
            listener.close()
            if new_listener is not None:new_listener.close()
            for sock in clients+accepted:sock.close()


if __name__=='__main__':unittest.main()
