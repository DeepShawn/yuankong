"""Hotspot limits target one private NIC + MAC, never general firewall policy."""
import ctypes
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'windows'))
from hotspot_access import HotspotAccess, NativeAccess, Filter, Condition, Value, Session, Sublayer, Action, address, INTERFACE, REMOTE_MAC, INBOUND, OUTBOUND

MAC='02:21:32:43:54:65'
GUID='11111111-2222-3333-4444-555555555555'

def snapshot(guid=GUID):return dict(state='On',private_adapters=[dict(id=guid,name='Hotspot private')],clients=[dict(address=MAC)])

class Engine:
    def __init__(self):self.entries=[];self.fail=False;self.closed=False
    def replace(self,entries):
        if self.fail:self.fail=False;raise OSError('driver rejected transaction')
        self.entries=list(entries)
    def live(self):return bool(self.entries)
    def close(self):self.closed=True;self.entries=[]

class ClientAccessTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'hotspot-blocks.json';self.engine=Engine()
        self.access=HotspotAccess(self.path,lambda:self.engine)
    def test_persisted_limit_only_targets_a_verified_connected_mac_and_the_private_guid(self):
        result=self.access.change(MAC,True,snapshot())
        self.assertTrue(result['confirmed']);self.assertEqual([dict(address=MAC,interface=GUID)],self.engine.entries)
        self.assertEqual([MAC],json.loads(self.path.read_text())['blocked'])
        state=self.access.sync(snapshot());self.assertTrue(state['clients'][0]['access_active'])
        self.assertIn('仍显示已连接',result['message'])
    def test_missing_ambiguous_or_stopped_hotspot_never_creates_global_filters(self):
        for state in [dict(state='Off'),dict(state='On',private_adapters=[]),dict(state='On',private_adapters=[{},{}])]:
            with self.subTest(state=state),self.assertRaises(ValueError):self.access.change(MAC,True,state)
        with self.assertRaisesRegex(ValueError,'当前未连接'):self.access.change('02:AA:BB:CC:DD:EE',True,snapshot())
        self.assertIsNone(self.access.engine);self.assertFalse(self.path.exists())
    def test_restarting_restores_only_when_the_new_private_adapter_is_confirmed(self):
        self.access.change(MAC,True,snapshot());other=Engine();restored=HotspotAccess(self.path,lambda:other)
        off=restored.sync(dict(state='Off'))
        self.assertFalse(off['client_access'][0]['active']);self.assertFalse(other.entries)
        changed=str(uuid.uuid4());restored.sync(snapshot(changed))
        self.assertEqual(changed,other.entries[0]['interface'])
        restored.sync(dict(state='Unavailable'))
        self.assertFalse(other.entries);self.assertEqual({MAC},restored.blocked)
    def test_driver_failure_never_reports_success_or_remembers_an_unapplied_block(self):
        self.engine.fail=True
        with self.assertRaisesRegex(OSError,'driver rejected'):self.access.change(MAC,True,snapshot())
        self.assertFalse(self.access.blocked);self.assertFalse(self.engine.entries);self.assertFalse(self.path.exists())
    def test_configuration_write_failure_restores_the_previous_filters(self):
        with mock.patch.object(self.access,'_save',side_effect=OSError('disk full')):
            with self.assertRaisesRegex(OSError,'disk full'):self.access.change(MAC,True,snapshot())
        self.assertFalse(self.engine.entries);self.assertFalse(self.access.blocked)
    def test_unblock_works_when_the_device_is_offline_or_hotspot_is_off(self):
        self.access.change(MAC,True,snapshot())
        self.access.change(MAC,False,dict(state='Off'))
        self.assertFalse(self.engine.entries);self.assertEqual([],json.loads(self.path.read_text())['blocked'])
    def test_mac_validation_rejects_groups_and_broadcast_but_accepts_private_addresses(self):
        for mac in ('FF:FF:FF:FF:FF:FF','01:00:5E:00:00:01','00:00:00:00:00:00','invalid'):
            with self.assertRaises(ValueError):address(mac)
        self.assertEqual(MAC,address('022132435465'));self.assertEqual(MAC,address('02-21-32-43-54-65'))
    def test_wfp_structures_have_the_official_x64_layout(self):
        self.assertEqual([200,40,16,72,72,20],[ctypes.sizeof(t) for t in (Filter,Condition,Value,Session,Sublayer,Action)])

@unittest.skipUnless(os.name=='nt','Windows Filtering Platform requires Windows')
class NativeClientAccessTest(unittest.TestCase):
    def test_native_filters_are_scoped_read_back_and_removed_without_touching_other_rules(self):
        from network_management import WindowsNetwork
        adapters=WindowsNetwork().run(dict(area='adapters',operation='status'))['adapters']
        self.assertTrue(adapters,'CI needs a real network interface for NET_LUID conversion')
        adapter=next((a for a in adapters if a['status']=='Up'),adapters[0])
        # A random locally administered address, not an existing hotspot client.
        mac='02:'+':'.join('%02X'%b for b in uuid.uuid4().bytes[:5])
        engine=NativeAccess()
        try:
            engine.replace([dict(address=mac,interface=adapter['id'])]);ids=list(engine.ids)
            self.assertEqual(2,len(ids));self.assertTrue(engine.live())
            filters=[engine.inspect(ident) for ident in ids]
            self.assertEqual({INBOUND,OUTBOUND},{f['layer'] for f in filters})
            for item in filters:
                self.assertEqual(0x1001,item['action']);self.assertEqual(2,len(item['conditions']))
                values={x['field']:x for x in item['conditions']}
                self.assertEqual(mac,values[REMOTE_MAC]['value']);self.assertGreater(values[INTERFACE]['value'],0)
            engine.replace([])
            self.assertTrue(all(engine.inspect(ident) is None for ident in ids))
        finally:engine.close()

if __name__=='__main__':unittest.main()
