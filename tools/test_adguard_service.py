"""Consent/controller tests and a temporary native SCM service, never real AdGuard."""
import ctypes as c
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'windows'))
import adguard_service as guard


class MemoryConsent:
    def __init__(self):self.value = None
    def read(self):return self.value
    def save(self, value):self.value = dict(value)
    def disable(self):self.value = None


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.binary = Path(self.temp.name) / 'AdGuardHome.exe'
        self.binary.write_bytes(b'approved local program fixture')
        self.info = dict(binary=str(self.binary), command_hash='a' * 64, state='stopped', service_exit=0)
        self.service = mock.Mock()
        self.service.inspect.side_effect = lambda:dict(self.info)
        self.consent = MemoryConsent()
        self.manager = guard.Companion(self.consent, self.service)

    def enable(self):
        _, approved = self.manager.prepare()
        self.manager.approve(approved)

    def test_no_consent_never_inspects_or_starts_a_service(self):
        self.assertFalse(self.manager.start_enabled()['enabled'])
        self.service.inspect.assert_not_called()
        self.service.start.assert_not_called()

    def test_approved_stopped_service_gets_only_one_start_request_and_reports_real_state(self):
        self.enable()
        def start():self.info['state']='starting'
        self.service.start.side_effect=start
        self.assertEqual('starting',self.manager.start_enabled()['state'])
        self.assertEqual('starting',self.manager.start_enabled()['state'])
        self.service.start.assert_called_once_with()

    def test_running_service_is_not_restarted(self):
        self.enable();self.info['state']='running'
        self.assertEqual('running',self.manager.start_enabled()['state'])
        self.service.start.assert_not_called()

    def test_failed_start_is_not_reported_as_running(self):
        self.enable();self.info['service_exit']=1067
        with self.assertRaisesRegex(OSError,'stopped'):self.manager.start_enabled()
        self.service.start.assert_called_once_with()

    def test_changed_executable_or_command_requires_local_reconfirmation(self):
        self.enable()
        self.binary.write_bytes(b'updated fixture')
        with self.assertRaisesRegex(ValueError,'变化'):self.manager.start_enabled()
        self.enable();self.info['command_hash']='b'*64
        with self.assertRaisesRegex(ValueError,'变化'):self.manager.start_enabled()
        self.service.start.assert_not_called()

    def test_service_changed_between_preview_and_confirmation_is_not_approved(self):
        _, approved=self.manager.prepare();self.info['command_hash']='c'*64
        with self.assertRaises(ValueError):self.manager.approve(approved)
        self.assertIsNone(self.consent.value)

    def test_cancelled_consent_cannot_start_and_does_not_stop_an_independent_service(self):
        self.enable();self.consent.disable()
        self.assertFalse(self.manager.start_enabled()['enabled'])
        self.service.start.assert_not_called();self.service.stop.assert_not_called()

    def test_paused_stopping_or_unknown_services_are_not_changed(self):
        self.enable()
        for state in ('paused','stopping','pausing','unknown'):
            self.info['state']=state
            with self.assertRaises(ValueError):self.manager.start_enabled()
        self.service.start.assert_not_called()

    def test_unknown_or_corrupt_consent_fails_closed(self):
        for value in ({}, {'service':'other'}, True, ['unexpected']):
            self.consent.value=value
            with self.assertRaises(ValueError):self.manager.start_enabled()
        self.service.start.assert_not_called()

    def test_not_an_arbitrary_executable_or_linked_program_launcher(self):
        for name in ('cmd.exe','powershell.exe'):
            path=self.binary.with_name(name);path.write_bytes(b'not AdGuard')
            with self.assertRaises(ValueError):guard.fingerprint(dict(self.info,binary=str(path)))
        other=self.binary.with_name('linked.exe');os.link(self.binary,other)
        with self.assertRaises(ValueError):guard.fingerprint(self.info)

    def test_redirected_setup_cannot_write_consent_or_operate_services(self):
        with mock.patch.object(sys,'stdin',io.StringIO('ENABLE\n')), mock.patch.object(guard,'RegistryConsent') as registry:
            with self.assertRaises(OSError):guard.setup()
            registry.assert_not_called()

    def test_background_entry_without_grant_never_constructs_scm_client(self):
        with mock.patch.object(guard.os,'name','nt'), mock.patch.object(guard,'RegistryConsent',return_value=self.consent), \
             mock.patch.object(guard,'NativeService') as service, mock.patch.object(sys,'argv',['tool','--start-if-enabled']), \
             mock.patch('builtins.print'):
            self.assertEqual(0,guard.main());service.assert_not_called()


@unittest.skipUnless(os.name=='nt','Native Windows service and registry APIs')
class NativeServiceTest(unittest.TestCase):
    def test_batch_already_running_new_start_and_upgrade_call_the_companion_without_changing_exit_status(self):
        fixture=ROOT/'windows/build-win/AdGuardHome.exe'
        self.assertTrue(fixture.is_file())
        with tempfile.TemporaryDirectory(prefix='guardian-batch-') as folder:
            root=Path(folder);(root/'python').mkdir();(root/'state/LanCmd').mkdir(parents=True)
            for name in ('python/python.exe','python/pythonw.exe','LanCmdAgent.exe'):
                shutil.copyfile(fixture,root/name)
            for name in ('adguard_service.py','lan_command_agent.py'):(root/name).write_bytes(b'fixture')
            shutil.copyfile(ROOT/'windows/start.bat',root/'start.bat')
            marker=root/'probe';pid=root/'state/LanCmd/agent.pid'
            env=dict(os.environ,APPDATA=str(root/'state'),ADGUARD_TEST_MARKER=str(marker),
                     ADGUARD_TEST_PIDFILE=str(pid),ADGUARD_TEST_BATCH=str(root/'start.bat'),LANCMD_UPGRADE_STARTED='1')
            for mode,expected in (('already',0),('new',0),('upgrade',2)):
                if pid.exists():pid.unlink()
                if mode=='already':pid.write_text(str(os.getpid()))
                if marker.exists():marker.unlink()
                command='"'+os.environ.get('COMSPEC',r'C:\Windows\System32\cmd.exe')+'" /d /v:off /c ""%ADGUARD_TEST_BATCH%"'+(' --upgrade' if mode=='upgrade' else '')+'"'
                result=subprocess.run(command,env=env,input=b'\r\n',capture_output=True,timeout=15)
                self.assertEqual(expected,result.returncode,(result.stdout+result.stderr).decode('utf-8','replace'))
                self.assertEqual('called',marker.read_text())
                if mode=='already':self.assertEqual(str(os.getpid()),pid.read_text())

    def test_registered_fixture_is_started_observed_and_stopped_with_real_scm(self):
        import winreg
        name='LocalGuardianFixture_'+uuid.uuid4().hex
        key=r'SOFTWARE\LocalGuardianFixture_'+uuid.uuid4().hex
        executable=ROOT/'windows/build-win/AdGuardHome.exe'
        self.assertTrue(executable.is_file(),'Compile the service fixture before the native test')
        api=c.WinDLL('advapi32',use_last_error=True)
        api.OpenSCManagerW.argtypes=[c.c_wchar_p,c.c_wchar_p,c.c_uint32];api.OpenSCManagerW.restype=c.c_void_p
        api.CreateServiceW.argtypes=[c.c_void_p,c.c_wchar_p,c.c_wchar_p,c.c_uint32,c.c_uint32,c.c_uint32,c.c_uint32,
                                    c.c_wchar_p,c.c_wchar_p,c.c_void_p,c.c_wchar_p,c.c_wchar_p,c.c_wchar_p]
        api.CreateServiceW.restype=c.c_void_p
        api.DeleteService.argtypes=[c.c_void_p];api.DeleteService.restype=c.c_int
        api.CloseServiceHandle.argtypes=[c.c_void_p];api.CloseServiceHandle.restype=c.c_int
        scm=api.OpenSCManagerW(None,None,3)
        self.assertTrue(scm,c.get_last_error());handle=None
        try:
            command=subprocess.list2cmdline([str(executable),name])
            handle=api.CreateServiceW(scm,name,name,0xF01FF,0x10,3,1,command,None,None,None,None,None)
            self.assertTrue(handle,c.get_last_error())
            with mock.patch.object(guard,'SERVICE',name):
                native=guard.NativeService();consent=guard.RegistryConsent(key)
                manager=guard.Companion(consent,native)
                try:
                    self.assertIsNone(consent.read())
                    info,approved=manager.prepare();self.assertEqual('stopped',info['state'])
                    manager.approve(approved);self.assertEqual(approved,consent.read())
                    manager.start_enabled()
                    deadline=time.monotonic()+10
                    while native.inspect()['state']!='running':
                        if time.monotonic()>=deadline:self.fail('Fixture did not reach SERVICE_RUNNING')
                        time.sleep(.05)
                    self.assertEqual('running',manager.start_enabled()['state'])
                    native.stop()
                    deadline=time.monotonic()+10
                    while native.inspect()['state']!='stopped':
                        if time.monotonic()>=deadline:self.fail('Fixture did not reach SERVICE_STOPPED')
                        time.sleep(.05)
                    consent.disable();self.assertIsNone(consent.read())
                    self.assertFalse(manager.start_enabled()['enabled'])
                finally:
                    try:native.stop()
                    except OSError:pass
                    deadline=time.monotonic()+10
                    while native.inspect()['state']!='stopped' and time.monotonic()<deadline:time.sleep(.05)
                    consent.disable()
                    try:winreg.DeleteKeyEx(winreg.HKEY_LOCAL_MACHINE,key,winreg.KEY_WOW64_64KEY)
                    except FileNotFoundError:pass
        finally:
            if handle:
                api.DeleteService(handle);api.CloseServiceHandle(handle)
            api.CloseServiceHandle(scm)


if __name__=='__main__':unittest.main()
