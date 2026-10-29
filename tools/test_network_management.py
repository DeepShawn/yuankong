"""Network changes stay asynchronous, authenticated, idempotent and secret-free."""
import ctypes
import io
import json
import os
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest import mock
from types import SimpleNamespace
import uuid
import xml.etree.ElementTree as E

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'windows'))
from native_wifi import NativeWifi, Guid, Interface, Available, Profile, ConnectionParameters, profile_xml
from network_management import NetworkManagement, WindowsNetwork, NetworkHardware, NETWORK_CLASSES, validate

class Error(Exception):pass

class FakeNetwork:
    def __init__(self):self.calls=[];self.release=threading.Event();self.entered=threading.Event();self.block=False;self.pair=False
    def read(self,area):return dict(supported=True,state='On',name='office',interfaces=[],devices=[],adapters=[])
    def perform(self,area,operation,params,challenge):
        self.calls.append((area,operation,dict(params)));self.entered.set()
        if self.block:self.release.wait(4)
        if self.pair:
            answer=challenge(dict(type='pairing',id='b'*32,kind='ProvidePin',display_code=''))
            if not answer['confirm']:raise ValueError('用户拒绝配对')
            raise ValueError('设备拒绝 PIN '+answer['pin'])
        if params.get('password'):raise ValueError('网卡拒绝密码 '+params['password'])
        return dict(confirmed=True,message='已确认')

class NetworkJobsTest(unittest.TestCase):
    def setUp(self):self.backend=FakeNetwork();self.manager=NetworkManagement(self.backend,grace=0)
    def call(self,owner='phone',**q):return self.manager.request(q,owner,Error)
    def submit(self,**fields):return self.call(action='submit',area='hotspot',operation='start',confirm=True,**fields)
    def wait(self,ident):
        end=time.monotonic()+4
        while time.monotonic()<end:
            result=self.call(job=ident)
            if result['job'].get('finished'):return result
            time.sleep(.005)
        self.fail('Network worker did not finish')
    def test_slow_mutation_returns_job_without_holding_rpc_lock(self):
        self.backend.block=True
        result=self.submit();ident=result['job']['id'];self.assertTrue(self.backend.entered.wait(1))
        try:
            start=time.monotonic();self.assertTrue(self.call(job=ident)['busy']);self.assertLess(time.monotonic()-start,.15)
            with self.assertRaisesRegex(Error,'正在执行'):self.submit()
            self.assertTrue(self.manager.busy())
        finally:self.backend.release.set();self.wait(ident)
        self.assertFalse(self.manager.busy());self.assertEqual(1,len(self.backend.calls))
    def test_same_request_survives_disconnect_without_duplicate_submission(self):
        ident=uuid.uuid4().hex
        first=self.submit(request_id=ident);self.wait(first['job']['id'])
        second=self.submit(request_id=ident)
        self.assertEqual(first['job']['id'],second['job']['id']);self.assertEqual(1,len(self.backend.calls))
        self.assertEqual(first['job']['id'],self.call(request_id=ident)['job']['id'])
        with self.assertRaises(Error):self.call(action='submit',area='hotspot',operation='stop',confirm=True,request_id=ident)
    def test_owner_and_confirmation_are_required(self):
        with self.assertRaises(Error):self.submit(owner='')
        with self.assertRaises(Error):self.call(action='submit',area='hotspot',operation='start')
        first=self.submit();self.wait(first['job']['id'])
        with self.assertRaises(Error):self.call(owner='other',job=first['job']['id'])
        self.assertIsNone(self.call(owner='other',request_id=first['job']['request_id'])['job'])
    def test_passwords_are_removed_from_public_errors_results_and_worker_memory(self):
        secret='correct<&secret'
        result=self.call(action='submit',area='hotspot',operation='configure',params=dict(name='office',password=secret),confirm=True)
        final=self.wait(result['job']['id'])
        self.assertEqual('failed',final['job']['status']);self.assertNotIn(secret,json.dumps(final))
        self.assertEqual([],self.manager.jobs[result['job']['id']]['secrets'])
    def test_pairing_needs_explicit_phone_answer_and_never_leaks_entered_pin(self):
        self.backend.pair=True
        result=self.call(action='submit',area='bluetooth',operation='pair',params=dict(id='Bluetooth#test'),confirm=True)
        ident=result['job']['id'];end=time.monotonic()+2
        while self.call(job=ident)['job']['status']!='awaiting_input' and time.monotonic()<end:time.sleep(.005)
        question=self.call(job=ident)['job']['question'];self.assertEqual('ProvidePin',question['kind'])
        with self.assertRaises(Error):self.call(owner='other',action='answer',job=ident,question=question['id'],accept=True,pin='9876')
        with self.assertRaises(Error):self.call(action='answer',job=ident,question='wrong',accept=True,pin='9876')
        self.call(action='answer',job=ident,question=question['id'],accept=True,pin='9876')
        final=self.wait(ident);self.assertNotIn('9876',json.dumps(final));self.assertIn('[已隐藏]',final['job']['message'])
        self.assertIsNone(final['job']['question'])
    def test_declining_pairing_does_not_submit_a_pin(self):
        self.backend.pair=True
        result=self.call(action='submit',area='bluetooth',operation='pair',params=dict(id='device'),confirm=True)
        ident=result['job']['id'];end=time.monotonic()+2
        while not self.call(job=ident)['job'].get('question') and time.monotonic()<end:time.sleep(.005)
        self.call(action='answer',job=ident,question='b'*32,accept=False)
        self.assertIn('拒绝',self.wait(ident)['job']['message'])
    def test_separate_phones_never_receive_each_others_refresh_job(self):
        entered=threading.Event();release=threading.Event()
        original=self.backend.read
        def read(area):entered.set();release.wait(3);return original(area)
        self.backend.read=read
        first=self.call(action='refresh');self.assertTrue(entered.wait(1))
        try:
            second=self.call(owner='other',action='refresh')
            self.assertNotEqual(first['job']['id'],second['job']['id'])
        finally:release.set();self.wait(first['job']['id'])
    def test_finished_mutation_includes_fresh_state_and_failed_refresh_is_explicit(self):
        result=self.submit();final=self.wait(result['job']['id'])
        self.assertEqual('On',final['snapshot']['state'])
        self.backend.read=mock.Mock(side_effect=OSError('设备不可用'))
        result=self.call(action='refresh',area='bluetooth');final=self.wait(result['job']['id'])
        actual=self.call(area='bluetooth',job=result['job']['id'])
        self.assertFalse(actual['snapshot']['supported']);self.assertIn('设备不可用',final['job']['message'])

class NetworkValidationTest(unittest.TestCase):
    def test_device_changes_require_exact_instance_boolean_and_current_area(self):
        with self.assertRaises(ValueError):validate('bluetooth','device',dict(instance='device',enabled=1))
        backend=WindowsNetwork();backend.hw=mock.Mock()
        backend.hardware=mock.Mock(return_value=[dict(id='net-1',kind='net',enabled=True,problem=0)])
        with mock.patch('network_management.ctypes_admin',return_value=True):
            with self.assertRaisesRegex(ValueError,'当前分类'):backend.perform('bluetooth','device',dict(instance='net-1',enabled=False),None)
            backend.hw.set_enabled.assert_not_called()
            backend.hw.status.return_value=dict(enabled=False,problem=22)
            result=backend.perform('wifi','device',dict(instance='net-1',enabled=False),None)
        self.assertTrue(result['confirmed']);backend.hw.set_enabled.assert_called_once_with('net-1',False)
    def test_device_changes_never_auto_elevate_or_target_usb_parent_classes(self):
        backend=WindowsNetwork();backend.hw=mock.Mock()
        backend.hardware=mock.Mock(return_value=[dict(id='bt-1',kind='bluetooth',enabled=True,problem=0)])
        with mock.patch('network_management.ctypes_admin',return_value=False),self.assertRaisesRegex(ValueError,'管理员'):
            backend.perform('bluetooth','device',dict(instance='bt-1',enabled=False),None)
        backend.hw.set_enabled.assert_not_called()
        device=NetworkHardware.__new__(NetworkHardware)
        with mock.patch('network_management.change_state',return_value={}) as change:
            device.set_enabled('bt-1',False)
        self.assertEqual(NETWORK_CLASSES,change.call_args.args[2]);self.assertEqual(2,len(NETWORK_CLASSES))
    def test_profile_xml_escapes_credentials_and_uses_exact_ssid_bytes(self):
        ssid='办公<&WiFi'.encode().hex();secret='test<&password'
        xml=profile_xml(ssid,'办公<&WiFi',7,secret)
        root=E.fromstring(xml);ns={'w':'http://www.microsoft.com/networking/WLAN/profile/v1'}
        self.assertEqual(ssid.upper(),root.find('.//w:hex',ns).text)
        self.assertEqual(secret,root.find('.//w:keyMaterial',ns).text)
        self.assertEqual('manual',root.find('w:connectionMode',ns).text)
        self.assertNotIn('<&password',xml)
    def test_unsafe_or_unsupported_settings_are_rejected_before_system_calls(self):
        invalid=[('hotspot','configure',dict(name='a',password='short')),('hotspot','configure',dict(name='中'*12,password='12345678')),
                 ('wifi','connect',dict(interface='not-guid')),('adapters','dns',dict(adapter=str(uuid.uuid4()),servers=['hostname'])),
                 ('bluetooth','radio',dict(name='Bluetooth',enabled=1))]
        for area,op,params in invalid:
            with self.subTest(area=area,op=op),self.assertRaises(ValueError):validate(area,op,params)
        with self.assertRaisesRegex(ValueError,'企业'):profile_xml('616263','abc',6,'12345678')
    def test_open_wifi_never_adds_a_shared_key(self):
        self.assertNotIn('sharedKey',profile_xml('616263','abc',1))
    def test_dns_allows_ipv4_and_ipv6_and_empty_restores_automatic(self):
        adapter=str(uuid.uuid4())
        self.assertEqual(['1.1.1.1','2606:4700:4700::1111'],validate('adapters','dns',dict(adapter=adapter,servers=['1.1.1.1','2606:4700:4700::1111']))['servers'])
        self.assertEqual([],validate('adapters','dns',dict(adapter=adapter,servers=[]))['servers'])

class NetworkTransportTest(unittest.TestCase):
    def process(self,lines):
        class Input(io.StringIO):
            def close(self):self.sent=self.getvalue();super().close()
        return SimpleNamespace(stdin=Input(),stdout=io.StringIO(lines),stderr=io.StringIO(),wait=mock.Mock(return_value=0))
    def test_helper_receives_password_only_on_stdin_and_reports_native_failure(self):
        process=self.process('123\n'+json.dumps(dict(ok=False,error='Access denied'))+'\n')
        request=dict(area='hotspot',operation='configure',name='office',password='unique-network-secret')
        with mock.patch('network_management.subprocess.Popen',return_value=process) as popen:
            with self.assertRaisesRegex(OSError,'Access denied'):WindowsNetwork().run(request)
        self.assertEqual(request,json.loads(process.stdin.sent))
        self.assertNotIn(request['password'],' '.join(popen.call_args.args[0]))
        self.assertEqual(0x08000000,popen.call_args.kwargs['creationflags'])
        self.assertTrue(process.stdout.closed);self.assertTrue(process.stderr.closed)
    def test_unhandled_pairing_challenge_is_declined_never_silently_accepted(self):
        event=dict(type='pairing',id='b'*32,kind='ConfirmOnly')
        process=self.process(json.dumps(event)+'\n'+json.dumps(dict(ok=True,data={}))+'\n')
        with mock.patch('network_management.subprocess.Popen',return_value=process):
            WindowsNetwork().run(dict(area='bluetooth',operation='pair',id='device'))
        response=json.loads(process.stdin.sent.splitlines()[1])
        self.assertEqual(dict(confirm=False,id='b'*32),response)

@unittest.skipUnless(os.name=='nt','Requires real Windows WLAN and WinRT metadata')
class NativeNetworkTest(unittest.TestCase):
    def test_native_network_and_bluetooth_inventory_is_read_only_and_has_real_problem_codes(self):
        items=WindowsNetwork().hardware()
        self.assertIsInstance(items,list)
        for item in items:
            self.assertIn(item['kind'],('net','bluetooth'));self.assertIsInstance(item['id'],str)
            self.assertIsInstance(item['problem'],int)
    def test_native_wlan_structures_match_windows_abi_and_service_errors_are_explicit(self):
        self.assertEqual([16,532,628,516,40],[ctypes.sizeof(t) for t in (Guid,Interface,Available,Profile,ConnectionParameters)])
        try:result=NativeWifi().status()
        except OSError as exc:self.assertIn('Windows',str(exc))
        else:self.assertIsInstance(result['interfaces'],list)
    def test_winrt_pairing_and_discovery_callbacks_bind_without_runspace(self):
        result=WindowsNetwork().run(dict(area='selftest',operation='status'))
        self.assertTrue(result['pairing_bridge']);self.assertTrue(result['discovery_bridge'])
    def test_native_adapter_snapshot_has_stable_ids_and_no_passwords(self):
        result=WindowsNetwork().read('adapters')
        self.assertTrue(result['supported']);self.assertNotIn('password',json.dumps(result).lower())
        for item in result['adapters']:uuid.UUID(item['id'])
    def test_bluetooth_snapshot_handles_a_machine_without_physical_radios(self):
        result=WindowsNetwork().read('bluetooth')
        self.assertIsInstance(result['radios'],list);self.assertIsInstance(result['devices'],list)

if __name__=='__main__':unittest.main()
