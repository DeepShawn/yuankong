"""A busy drive must not blank the file manager or occupy all RPC requests."""
import os
from pathlib import Path
import sys
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'windows'))
from storage_reads import BoundedReads,DriveUsage


class StorageReadTest(unittest.TestCase):
    def test_slow_usb_capacity_does_not_block_other_drives_and_retries_do_not_spawn_more_readers(self):
        entered=threading.Event();release=threading.Event();calls=[]
        def usage(root):
            calls.append(root)
            if root=='E:\\':entered.set();release.wait(3)
            return SimpleNamespace(total=100,free=60)
        cache=DriveUsage(usage,ttl=10)
        try:
            start=time.monotonic();result=cache.snapshot(['C:\\','E:\\'],budget=.03)
            self.assertLess(time.monotonic()-start,.5);self.assertTrue(entered.is_set())
            self.assertTrue(result[0]['ready']);self.assertFalse(result[1]['ready'])
            self.assertTrue(result[1]['pending'])
            for _ in range(5):cache.snapshot(['C:\\','E:\\'],budget=0)
            self.assertEqual(1,calls.count('E:\\'))
        finally:release.set()
        self.assertTrue(cache.entries['E:\\']['done'].wait(1))
        result=cache.snapshot(['C:\\','E:\\'],budget=0)
        self.assertTrue(result[1]['ready']);self.assertEqual(60,result[1]['free'])

    def test_timed_out_listing_remains_single_flight_and_another_directory_can_load(self):
        entered=threading.Event();release=threading.Event();calls=[];reads=BoundedReads(limit=2)
        def slow():calls.append(1);entered.set();release.wait(3);return {'items':['reserve.dat']}
        try:
            for _ in range(3):
                with self.assertRaises(TimeoutError):reads.read('E:\\',slow,timeout=.01)
            self.assertEqual(1,len(calls));self.assertTrue(entered.is_set())
            self.assertEqual({'items':['notes']},reads.read('C:\\',lambda:{'items':['notes']},timeout=.2))
        finally:release.set()
        self.assertTrue(reads.pending['E:\\']['done'].wait(1))
        self.assertEqual({'items':['reserve.dat']},reads.read('E:\\',slow,timeout=.1))
        self.assertEqual(1,len(calls))

    def test_blocked_reads_are_bounded_and_completed_reads_are_released(self):
        release=threading.Event();reads=BoundedReads(limit=2)
        try:
            for name in ('a','b'):
                with self.assertRaises(TimeoutError):reads.read(name,lambda:release.wait(3),timeout=.01)
            with self.assertRaises(TimeoutError):reads.read('c',lambda:None,timeout=.01)
            self.assertEqual(2,len(reads.pending))
        finally:release.set()
        for job in list(reads.pending.values()):self.assertTrue(job['done'].wait(1))
        self.assertEqual('ok',reads.read('c',lambda:'ok'))
        self.assertFalse(reads.pending)

    def test_storage_inventory_does_not_probe_each_drive_root_synchronously(self):
        import lan_command_agent as agent
        cache=mock.Mock();cache.snapshot.return_value=[{'path':'E:\\','total':0,'free':0,'ready':False,'pending':True}]
        fake=SimpleNamespace(kernel32=SimpleNamespace(GetLogicalDrives=lambda:(1<<2)|(1<<4)))
        with mock.patch.object(agent,'IS_WIN',True),mock.patch.object(agent.ctypes,'windll',fake,create=True),mock.patch.object(agent,'_drive_usage',cache),mock.patch.object(agent.os.path,'exists',side_effect=AssertionError('synchronous disk probe')):
            result=agent.cmd_storage()
        cache.snapshot.assert_called_once_with(['C:\\','E:\\'])
        self.assertFalse(result['drives'][0]['ready'])
        self.assertEqual(0,result['drives'][0]['percent'])


if __name__=='__main__':unittest.main()
