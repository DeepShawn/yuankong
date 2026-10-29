"""Startup settings survive in the install folder; network work stays off RPC threads."""
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"windows"))
from startup_network import StartupNetwork, WindowsStartupNetwork, PERSISTENCE_NOTICE


class Error(Exception): pass


class StartupNetworkTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.backend=mock.Mock()
        self.backend.registration.return_value=dict(enabled=False,command="",other_installation=False)
        self.backend.hotspot.return_value=dict(supported=True,state="On",name="My hotspot",clients=0)
        self.manager=StartupNetwork(self.root,self.backend)

    def call(self,**q):return self.manager.request(q,Error)

    def wait(self):
        deadline=time.monotonic()+5
        while self.manager.job["status"]=="running":
            if time.monotonic()>deadline:self.fail("Startup network task did not settle")
            time.sleep(.01)

    def test_configure_saves_only_explicit_switches_and_calls_regular_registration(self):
        self.backend.register.return_value=dict(enabled=True,command="receiver")
        self.backend.firewall.return_value=dict(enabled=True,remote="LocalSubnet")
        options=dict(autostart=True,auto_hotspot=True,auto_firewall=True)
        self.call(action="configure",settings=options);self.wait()
        self.assertEqual(options,json.loads((self.root/"startup-network.json").read_text()))
        self.backend.register.assert_called_once_with(True)
        self.backend.hotspot.assert_called_once_with(True)
        self.assertEqual("completed",self.manager.job["status"])
        self.assertIn("还原",self.call()["persistence_notice"])

    def test_boot_reuses_portable_options_without_recreating_disabled_run_entries(self):
        (self.root/"startup-network.json").write_text(json.dumps(dict(autostart=True,auto_hotspot=True,auto_firewall=True)))
        self.backend.firewall.return_value=dict(enabled=True)
        self.manager.boot();self.wait()
        self.backend.hotspot.assert_called_once_with(True);self.backend.firewall.assert_called_once()
        self.backend.register.assert_not_called()

    def test_slow_hotspot_does_not_hold_status_lock_or_spawn_parallel_network_work(self):
        entered=threading.Event();release=threading.Event()
        def delayed(start):entered.set();release.wait(5);return dict(state="On",supported=True)
        self.backend.hotspot.side_effect=delayed
        self.call(action="refresh");self.assertTrue(entered.wait(1))
        try:
            start=time.monotonic();status=self.call();self.assertLess(time.monotonic()-start,.2)
            self.assertEqual("running",status["job"]["status"])
            with self.assertRaises(Error):self.call(action="apply")
            self.assertEqual(1,self.backend.hotspot.call_count)
        finally:release.set();self.wait()

    def test_permission_failure_does_not_claim_all_settings_were_applied_or_skip_hotspot(self):
        self.backend.register.return_value=dict(enabled=True)
        self.backend.firewall.side_effect=ValueError("需要管理员接收端")
        self.call(action="configure",settings=dict(autostart=True,auto_hotspot=True,auto_firewall=True));self.wait()
        result=self.call()
        self.assertEqual("failed",result["job"]["status"])
        self.assertIn("管理员",result["job"]["error"])
        self.assertEqual("On",result["hotspot"]["state"])

    def test_invalid_and_unknown_options_are_rejected_before_any_system_action(self):
        for options in ({},dict(autostart=1,auto_hotspot=True,auto_firewall=True),
                        dict(autostart=True,auto_hotspot=True,auto_firewall=True,disable_firewall=True)):
            with self.assertRaises(Error):self.call(action="configure",settings=options)
        self.backend.register.assert_not_called();self.backend.firewall.assert_not_called()


class NarrowFirewallTest(unittest.TestCase):
    def test_rules_are_program_port_and_local_subnet_scoped_and_never_disable_firewall(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/"python").mkdir();(root/"python/pythonw.exe").touch()
            calls=[]
            def netsh(args):
                calls.append(args)
                return (1,"") if "show" in args else (0,"ok")
            backend=WindowsStartupNetwork(root,lambda:True,lambda:(47823,47824),netsh)
            result=backend.firewall()
            self.assertEqual("LocalSubnet",result["remote"])
            edits=[args for args in calls if "add" in args]
            self.assertEqual(2,len(edits))
            for args in edits:
                self.assertIn("remoteip=LocalSubnet",args);self.assertIn("program="+str(root/"python/pythonw.exe"),args)
                self.assertTrue(any(value.startswith("localport=") for value in args))
                self.assertFalse("off" in args or "state" in args)
            backend.admin=lambda:False
            with self.assertRaisesRegex(ValueError,"管理员"):backend.firewall()

    def test_hotspot_request_must_report_actual_on_state(self):
        backend=WindowsStartupNetwork(Path.cwd(),lambda:False,lambda:(1,2),None)
        result=mock.Mock(returncode=0,stdout=b'{"supported":true,"state":"Off"}',stderr=b"")
        with mock.patch("startup_network.subprocess.run",return_value=result):
            with self.assertRaisesRegex(ValueError,"未确认"):backend.hotspot(True)
        result.stdout=b'{"supported":true,"state":"On","password":"never expose","name":"office"}'
        with mock.patch("startup_network.subprocess.run",return_value=result) as run:
            state=backend.hotspot(True)
            self.assertEqual("On",state["state"]);self.assertNotIn("password",state)
            self.assertEqual(0x08000000,run.call_args.kwargs["creationflags"])


@unittest.skipUnless(os.name=="nt","Requires Windows PowerShell and public WinRT API")
class NativeHotspotStatusTest(unittest.TestCase):
    def test_unavailable_server_or_adapter_is_reported_without_windows_prompts(self):
        backend=WindowsStartupNetwork(ROOT/"windows",lambda:False,lambda:(47823,47824),None)
        result=backend.hotspot(False)
        self.assertIn(result["state"],("On","Off","Unknown","InTransition","Unavailable"))
        self.assertNotIn("password",result)
        if result["state"]=="Unavailable":self.assertTrue(result["error"])


if __name__=="__main__":unittest.main()
