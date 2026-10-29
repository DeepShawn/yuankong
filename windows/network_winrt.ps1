$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false)
$request=[Console]::In.ReadLine() | ConvertFrom-Json

function Await($operation,[Type]$type,[int]$timeout=25000) {
    if ($type) {
        $method=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetGenericArguments().Count -eq 1 -and
            $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        } | Select-Object -First 1
        $task=$method.MakeGenericMethod($type).Invoke($null,@($operation))
    } else {
        $method=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and !$_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction'
        } | Select-Object -First 1
        $task=$method.Invoke($null,@($operation))
    }
    if (!$task.Wait($timeout)) { throw '等待 Windows 设备接口超时' }
    if ($type) { return $task.Result }
}

function Load-Runtime {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null=[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]
    $null=[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]
    $null=[Windows.Devices.Radios.Radio,Windows.System.Devices,ContentType=WindowsRuntime]
    $null=[Windows.Devices.Enumeration.DeviceInformation,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
    $null=[Windows.Devices.Bluetooth.BluetoothDevice,Windows.Devices.Bluetooth,ContentType=WindowsRuntime]
    $null=[Windows.Devices.Bluetooth.BluetoothLEDevice,Windows.Devices.Bluetooth,ContentType=WindowsRuntime]
}

function Radios {
    return @(Await ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]]))
}

function Hotspot-Manager {
    $profile=[Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
    if (!$profile) { throw '没有可共享的网络连接，请先连接网络' }
    $cap=[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::GetTetheringCapabilityFromConnectionProfile($profile)
    if ($cap.ToString() -ne 'Enabled') { throw ('此网络或无线网卡不支持移动热点：'+$cap.ToString()) }
    return [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
}

function Set-Hotspot($manager,[bool]$on) {
    $op=if($on){$manager.StartTetheringAsync()}else{$manager.StopTetheringAsync()}
    $type=[Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]
    $result=Await $op $type
    if($result.Status.ToString() -ne 'Success'){throw ('移动热点操作失败：'+$result.Status.ToString()+' '+$result.AdditionalErrorMessage)}
}

function Hotspot-Status($manager) {
    $config=$manager.GetCurrentAccessPointConfiguration()
    $clients=@($manager.GetTetheringClients() | ForEach-Object {
        @{address=$_.MacAddress;hosts=@($_.HostNames | ForEach-Object {$_.CanonicalName})}
    })
    $band=if($config.PSObject.Properties.Name -contains 'Band'){$config.Band.ToString()}else{'SystemManaged'}
    return @{supported=$true;state=$manager.TetheringOperationalState.ToString();name=$config.Ssid;
        band=$band;band_supported=($config.PSObject.Properties.Name -contains 'Band');clients=$clients;
        client_count=[int]$manager.ClientCount;max_clients=[int]$manager.MaxClientCount;individual_disconnect=$false}
}

function Bluetooth-Status {
    Load-PairingBridge
    $radios=@(Radios | Where-Object {$_.Kind.ToString() -eq 'Bluetooth'} | ForEach-Object {
        @{name=$_.Name;kind='Bluetooth';state=$_.State.ToString()}
    })
    $properties=[string[]]@('System.Devices.Aep.DeviceAddress','System.Devices.Aep.IsConnected','System.Devices.Aep.IsPresent')
    $devices=@()
    $watchers=@()
    try {
      foreach($kind in @('classic','ble')) {
        $selector=if($kind -eq 'classic'){[Windows.Devices.Bluetooth.BluetoothDevice]::GetDeviceSelector()}else{[Windows.Devices.Bluetooth.BluetoothLEDevice]::GetDeviceSelector()}
        $collection=[Windows.Devices.Enumeration.DeviceInformationCollection,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
        $found=Await ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($selector,$properties,[Windows.Devices.Enumeration.DeviceInformationKind]::AssociationEndpoint)) $collection
        foreach($device in $found) {
            $devices+=@{id=$device.Id;name=$device.Name;kind=$kind;paired=[bool]$device.Pairing.IsPaired;
                can_pair=[bool]$device.Pairing.CanPair;address=[string]$device.Properties['System.Devices.Aep.DeviceAddress'];
                connected=[bool]$device.Properties['System.Devices.Aep.IsConnected'];present=[bool]$device.Properties['System.Devices.Aep.IsPresent']}
            if($devices.Count -ge 100){break}
        }
        $selector=if($kind -eq 'classic'){[Windows.Devices.Bluetooth.BluetoothDevice]::GetDeviceSelectorFromPairingState($false)}else{[Windows.Devices.Bluetooth.BluetoothLEDevice]::GetDeviceSelectorFromPairingState($false)}
        $watcher=[Windows.Devices.Enumeration.DeviceInformation]::CreateWatcher($selector,$properties,[Windows.Devices.Enumeration.DeviceInformationKind]::AssociationEndpoint)
        $watcherType=[Windows.Devices.Enumeration.DeviceWatcher,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
        $add=$watcherType.GetMethod('add_Added');$remove=$watcherType.GetMethod('remove_Added')
        $bridge=[NetworkDiscovery]::new([Windows.Devices.Enumeration.DeviceInformation])
        $handler=[Delegate]::CreateDelegate($add.GetParameters()[0].ParameterType,$bridge,$bridge.GetType().GetMethod('Added'))
        $token=$add.Invoke($watcher,@($handler))
        $watchers+=@{watcher=$watcher;bridge=$bridge;handler=$handler;token=$token;remove=$remove;kind=$kind}
        $watcher.Start()
      }
      [Threading.Thread]::Sleep(5000)
      foreach($entry in $watchers) {
        foreach($device in $entry.bridge.Snapshot()) {
            $devices+=@{id=$device.Id;name=$device.Name;kind=$entry.kind;paired=[bool]$device.Pairing.IsPaired;
                can_pair=[bool]$device.Pairing.CanPair;address=[string]$device.Properties['System.Devices.Aep.DeviceAddress'];
                connected=[bool]$device.Properties['System.Devices.Aep.IsConnected'];present=[bool]$device.Properties['System.Devices.Aep.IsPresent']}
        }
      }
    } finally {
        foreach($entry in $watchers) {
            try {$entry.watcher.Stop()} catch {}
            if($entry.remove.GetParameters()[0].ParameterType -eq $entry.handler.GetType()){$null=$entry.remove.Invoke($entry.watcher,@($entry.handler))}
            else {$null=$entry.remove.Invoke($entry.watcher,@($entry.token))}
        }
    }
    return @{supported=$true;radios=$radios;devices=@($devices | Sort-Object id -Unique)}
}

function Load-PairingBridge {
    # The WinRT callback executes on a native worker thread. A C# handler avoids
    # running PowerShell scriptblocks without a runspace on that thread.
    Add-Type -ReferencedAssemblies System.Web.Extensions -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Reflection;
using System.Web.Script.Serialization;
public sealed class NetworkDiscovery {
    readonly Type deviceType;
    readonly Dictionary<string,object> devices=new Dictionary<string,object>();
    public NetworkDiscovery(Type deviceType) { this.deviceType=deviceType; }
    public void Added(object sender,object device) {
        string id=(string)deviceType.GetProperty("Id").GetValue(device,null);
        lock(devices) { if(devices.Count<100) devices[id]=device; }
    }
    public object[] Snapshot() { lock(devices) { var data=new object[devices.Count];devices.Values.CopyTo(data,0);return data; } }
}
public sealed class NetworkPairingBridge {
    readonly Type argsType;
    public NetworkPairingBridge(Type argsType) { this.argsType=argsType; }
    public void Requested(object sender, object args) {
        object deferral=argsType.GetMethod("GetDeferral").Invoke(args,null);
        try {
            string kind=argsType.GetProperty("PairingKind").GetValue(args,null).ToString();
            string pin=(string)argsType.GetProperty("Pin").GetValue(args,null);
            string id=Guid.NewGuid().ToString("N");
            var json=new JavaScriptSerializer();
            Console.WriteLine(json.Serialize(new { type="pairing", id=id, kind=kind, display_code=pin??"" }));
            Console.Out.Flush();
            var input=Console.In.ReadLineAsync();
            if(!input.Wait(45000) || input.Result==null) return;
            var response=json.Deserialize<Dictionary<string,object>>(input.Result);
            if(!response.ContainsKey("id") || (string)response["id"]!=id || !response.ContainsKey("confirm") || !(bool)response["confirm"]) return;
            if(kind=="ProvidePin") {
                if(!response.ContainsKey("pin")) return;
                string provided=(string)response["pin"];
                if(provided.Length<1 || provided.Length>16) return;
                argsType.GetMethod("Accept",new Type[]{typeof(string)}).Invoke(args,new object[]{provided});
            } else argsType.GetMethod("Accept",Type.EmptyTypes).Invoke(args,null);
        } finally { argsType.GetMethod("GetDeferral").ReturnType.GetMethod("Complete").Invoke(deferral,null); }
    }
}
'@ | Out-Null
}

function Pair-Device($device) {
    $null=[Windows.Devices.Enumeration.DevicePairingRequestedEventArgs,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
    $null=[Windows.Devices.Enumeration.DeviceInformationCustomPairing,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
    Load-PairingBridge
    $custom=$device.Pairing.Custom
    $type=[Windows.Devices.Enumeration.DeviceInformationCustomPairing]
    $add=$type.GetMethod('add_PairingRequested')
    $remove=$type.GetMethod('remove_PairingRequested')
    $handlerType=$add.GetParameters()[0].ParameterType
    $bridge=[NetworkPairingBridge]::new([Windows.Devices.Enumeration.DevicePairingRequestedEventArgs])
    $handler=[Delegate]::CreateDelegate($handlerType,$bridge,$bridge.GetType().GetMethod('Requested'))
    $token=$add.Invoke($custom,@($handler))
    try {
        $kinds=[Windows.Devices.Enumeration.DevicePairingKinds]15
        $resultType=[Windows.Devices.Enumeration.DevicePairingResult,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
        $result=Await ($custom.PairAsync($kinds,[Windows.Devices.Enumeration.DevicePairingProtectionLevel]::Default)) $resultType 80000
        $status=$result.Status.ToString()
        if($status -notin @('Paired','AlreadyPaired')){throw ('蓝牙配对未完成：'+$status)}
        return @{paired=$true;status=$status;message='配对状态已确认，连接由 Windows 对应设备服务处理'}
    } finally {
        if($remove.GetParameters()[0].ParameterType -eq $handlerType){$null=$remove.Invoke($custom,@($handler))}
        else {$null=$remove.Invoke($custom,@($token))}
    }
}

function Adapter-Status {
    return @{supported=$true;adapters=@(Get-NetAdapter -ErrorAction Stop | ForEach-Object {
        $adapter=$_;$index=$adapter.ifIndex
        # Get-NetIPConfiguration internally queries both IP families and can
        # terminate the entire enumeration when one binding is absent.
        $addressNotice=''
        try {$addresses=@(Get-NetIPAddress -InterfaceIndex $index -ErrorAction Stop)}
        catch {$addresses=@();$addressNotice=$_.Exception.Message}
        try {$routes=@(Get-NetRoute -InterfaceIndex $index -ErrorAction Stop | Where-Object {$_.DestinationPrefix -in @('0.0.0.0/0','::/0')})}
        catch {$routes=@()}
        try {$dns=Get-DnsClientServerAddress -InterfaceIndex $index -ErrorAction Stop}
        catch {$dns=@()}
        @{id=$adapter.InterfaceGuid.ToString();index=$index;name=$adapter.Name;description=$adapter.InterfaceDescription;
            status=$adapter.Status.ToString();mac=$adapter.MacAddress;speed=[string]$adapter.LinkSpeed;
            ipv4=@($addresses | Where-Object {$_.AddressFamily.ToString() -eq 'IPv4'} | ForEach-Object {$_.IPAddress});
            ipv6=@($addresses | Where-Object {$_.AddressFamily.ToString() -eq 'IPv6'} | ForEach-Object {$_.IPAddress});
            address_notice=$addressNotice;gateways=@($routes | ForEach-Object {$_.NextHop});dns=@($dns.ServerAddresses | ForEach-Object {$_})}
    })}
}

try {
    if($request.area -eq 'adapters') {
        if($request.operation -ne 'status') {
            $adapter=@(Get-NetAdapter | Where-Object {$_.InterfaceGuid.ToString() -eq $request.adapter})
            if($adapter.Count -ne 1){throw '网卡已变化，请刷新并重新选择'}
            $index=$adapter[0].ifIndex
            switch($request.operation) {
                'enable' {$adapter[0] | Enable-NetAdapter -Confirm:$false}
                'disable' {$adapter[0] | Disable-NetAdapter -Confirm:$false}
                'dns' {if(@($request.servers).Count){Set-DnsClientServerAddress -InterfaceIndex $index -ServerAddresses ([string[]]$request.servers)}else{Set-DnsClientServerAddress -InterfaceIndex $index -ResetServerAddresses}}
                'renew' {& "$env:SystemRoot\System32\ipconfig.exe" /renew $adapter[0].Name | Out-Null;if($LASTEXITCODE){throw 'Windows 未确认 DHCP 续租成功；请检查该接口是否启用 DHCP'}}
                default {throw '无效的网卡操作'}
            }
        }
        $data=Adapter-Status
    } else {
        Load-Runtime
        if($request.area -eq 'selftest') {
            Load-PairingBridge
            $argsType=[Windows.Devices.Enumeration.DevicePairingRequestedEventArgs,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
            $customType=[Windows.Devices.Enumeration.DeviceInformationCustomPairing,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
            $add=$customType.GetMethod('add_PairingRequested')
            if(!$add){throw '系统缺少自定义蓝牙配对事件'}
            $bridge=[NetworkPairingBridge]::new($argsType)
            $handler=[Delegate]::CreateDelegate($add.GetParameters()[0].ParameterType,$bridge,$bridge.GetType().GetMethod('Requested'))
            $watcherType=[Windows.Devices.Enumeration.DeviceWatcher,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
            $collector=[NetworkDiscovery]::new([Windows.Devices.Enumeration.DeviceInformation])
            $discovery=[Delegate]::CreateDelegate($watcherType.GetMethod('add_Added').GetParameters()[0].ParameterType,$collector,$collector.GetType().GetMethod('Added'))
            $data=@{supported=$true;pairing_bridge=($null -ne $handler);discovery_bridge=($null -ne $discovery)}
        } elseif($request.area -eq 'radios') {
            $radios=@(Radios)
            if($request.operation -ne 'status') {
                $access=Await ([Windows.Devices.Radios.Radio]::RequestAccessAsync()) ([Windows.Devices.Radios.RadioAccessStatus])
                if($access.ToString() -ne 'Allowed'){throw ('Windows 不允许修改无线开关：'+$access.ToString())}
                $radio=@($radios | Where-Object {$_.Kind.ToString() -eq $request.kind -and $_.Name -eq $request.name})
                if($radio.Count -ne 1){throw '无线设备已变化或存在同名设备，请刷新'}
                $state=if($request.enabled){[Windows.Devices.Radios.RadioState]::On}else{[Windows.Devices.Radios.RadioState]::Off}
                $access=Await ($radio[0].SetStateAsync($state)) ([Windows.Devices.Radios.RadioAccessStatus])
                if($access.ToString() -ne 'Allowed' -or $radio[0].State -ne $state){throw 'Windows 未确认无线设备状态已切换'}
            }
            $data=@{supported=$true;radios=@($radios | ForEach-Object {@{name=$_.Name;kind=$_.Kind.ToString();state=$_.State.ToString()}})}
        } elseif($request.area -eq 'hotspot') {
            $manager=Hotspot-Manager
            switch($request.operation) {
                'start' {Set-Hotspot $manager $true}
                'stop' {Set-Hotspot $manager $false}
                'restart' {Set-Hotspot $manager $false;Set-Hotspot $manager $true}
                'configure' {
                    $old=$manager.GetCurrentAccessPointConfiguration();$wasOn=$manager.TetheringOperationalState.ToString() -eq 'On'
                    $type=[Windows.Networking.NetworkOperators.NetworkOperatorTetheringAccessPointConfiguration,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]
                    $config=[Activator]::CreateInstance($type);$config.Ssid=$request.name
                    $config.Passphrase=if($request.password){$request.password}else{$old.Passphrase}
                    if($config.PSObject.Properties.Name -contains 'Band') {
                        $bandType=$old.Band.GetType()
                        $band=@{auto='Auto';'2.4'='TwoPointFourGigahertz';'5'='FiveGigahertz'}[$request.band]
                        $config.Band=[Enum]::Parse($bandType,$band)
                    } elseif($request.band -ne 'auto'){throw '当前 Windows 不支持选择热点频段'}
                    try {
                        if($wasOn){Set-Hotspot $manager $false}
                        Await ($manager.ConfigureAccessPointAsync($config)) $null
                        if($wasOn){Set-Hotspot $manager $true}
                    } catch {
                        $reason=$_.Exception.Message
                        try {Await ($manager.ConfigureAccessPointAsync($old)) $null;if($wasOn){Set-Hotspot $manager $true}}
                        catch {$reason+='；恢复旧热点配置失败：'+$_.Exception.Message}
                        throw $reason
                    }
                }
                'status' {}
                default {throw '无效的热点操作'}
            }
            $data=Hotspot-Status $manager
        } elseif($request.area -eq 'bluetooth') {
            if($request.operation -ne 'status') {
                $properties=[string[]]@('System.Devices.Aep.IsConnected')
                $device=Await ([Windows.Devices.Enumeration.DeviceInformation]::CreateFromIdAsync($request.id,$properties,[Windows.Devices.Enumeration.DeviceInformationKind]::AssociationEndpoint)) ([Windows.Devices.Enumeration.DeviceInformation])
                if($request.operation -eq 'pair') {$data=Pair-Device $device}
                elseif($request.operation -eq 'unpair') {
                    $type=[Windows.Devices.Enumeration.DeviceUnpairingResult,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
                    $result=Await ($device.Pairing.UnpairAsync()) $type
                    if($result.Status.ToString() -notin @('Unpaired','AlreadyUnpaired')){throw ('取消配对失败：'+$result.Status.ToString())}
                    $data=@{paired=$false;status=$result.Status.ToString()}
                } else {throw '无效的蓝牙操作'}
            } else {$data=Bluetooth-Status}
        } else {throw '无效的网络设备分类'}
    }
    @{ok=$true;data=$data} | ConvertTo-Json -Depth 12 -Compress
} catch {
    @{ok=$false;error=$_.Exception.Message} | ConvertTo-Json -Depth 4 -Compress
}
