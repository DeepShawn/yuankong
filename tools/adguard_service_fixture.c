/* A temporary test service only. Never packaged as AdGuard Home. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

static SERVICE_STATUS_HANDLE handle;
static SERVICE_STATUS state;
static HANDLE stopped;

static void report(DWORD value)
{
    state.dwServiceType=SERVICE_WIN32_OWN_PROCESS;
    state.dwCurrentState=value;
    state.dwControlsAccepted=value==SERVICE_RUNNING ? SERVICE_ACCEPT_STOP : 0;
    state.dwWin32ExitCode=NO_ERROR;
    state.dwWaitHint=value==SERVICE_STOP_PENDING ? 2000 : 0;
    SetServiceStatus(handle,&state);
}

static VOID WINAPI control(DWORD code)
{
    if(code==SERVICE_CONTROL_STOP) { report(SERVICE_STOP_PENDING); SetEvent(stopped); }
}

static VOID WINAPI service_main(DWORD argc, LPWSTR *argv)
{
    (void)argc;
    stopped=CreateEventW(NULL,TRUE,FALSE,NULL);
    handle=RegisterServiceCtrlHandlerW(argv[0],control);
    if(!handle || !stopped)return;
    report(SERVICE_RUNNING);
    WaitForSingleObject(stopped,INFINITE);
    report(SERVICE_STOPPED);
    CloseHandle(stopped);
}

int wmain(int argc, wchar_t **argv)
{
    /* CLI probes for batch tests. Only ever write to explicitly supplied test
       paths; this executable is not included in the portable distribution. */
    const wchar_t *probe=_wgetenv(L"ADGUARD_TEST_MARKER");
    if(probe && argc>1 && wcscmp(argv[argc-1],L"--start-if-enabled")==0) {
        FILE *file=_wfopen(probe,L"wb");
        if(file) { fputs("called",file); fclose(file); }
        return 19; /* prove the companion cannot replace the receiver's status */
    }
    if(probe && argc==1) {
        const wchar_t *pid=_wgetenv(L"ADGUARD_TEST_PIDFILE");
        FILE *file=pid ? _wfopen(pid,L"wb") : NULL;
        if(file) { fputs("99999\n",file); fclose(file); }
        return 0;
    }
    if(argc!=2)return 2;
    SERVICE_TABLE_ENTRYW table[]={{argv[1],service_main},{NULL,NULL}};
    return StartServiceCtrlDispatcherW(table) ? 0 : (int)GetLastError();
}
