#Requires -Version 5.1
<#
.SYNOPSIS
Run Gradle with release signing passwords loaded from Windows Credential Manager.
.EXAMPLE
.\scripts\run_signed_gradle.ps1 :app:signingReport
.EXAMPLE
.\scripts\run_signed_gradle.ps1 :app:assembleGithubRelease
#>
[CmdletBinding()]
param(
    [string]$CredentialTarget,
    [string]$KeyCredentialTarget,
    [string]$KeystoreFile,
    [string]$KeyAlias,

    [Parameter(Mandatory, Position = 0, ValueFromRemainingArguments)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false

$root = Split-Path $PSScriptRoot -Parent
$gradlew = Join-Path $root 'gradlew.bat'
if (!(Test-Path -LiteralPath $gradlew -PathType Leaf)) {
    throw "Gradle wrapper not found: $gradlew"
}

if (!$CredentialTarget) {
    $CredentialTarget = if ($env:RELEASE_CREDENTIAL_TARGET) {
        $env:RELEASE_CREDENTIAL_TARGET
    } else {
        'trackercontrol_keystore'
    }
}
if (!$KeyCredentialTarget) {
    $KeyCredentialTarget = $env:RELEASE_KEY_CREDENTIAL_TARGET
}

if (-not ('TcCredentialManager' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class TcCredentialManager {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct CREDENTIAL {
        public uint Flags;
        public uint Type;
        public string TargetName;
        public string Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        public string TargetAlias;
        public string UserName;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, uint type, int reservedFlag, out IntPtr credentialPtr);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern void CredFree(IntPtr cred);

    public static string ReadGenericPassword(string target) {
        IntPtr credPtr;
        if (!CredRead(target, 1, 0, out credPtr)) {
            int err = Marshal.GetLastWin32Error();
            throw new Win32Exception(err, "Failed to read Windows Credential '" + target + "': " + new Win32Exception(err).Message);
        }
        try {
            CREDENTIAL cred = Marshal.PtrToStructure<CREDENTIAL>(credPtr);
            if (cred.CredentialBlob == IntPtr.Zero || cred.CredentialBlobSize == 0) {
                throw new InvalidOperationException("Windows Credential '" + target + "' has an empty password.");
            }
            return Marshal.PtrToStringUni(cred.CredentialBlob, (int)(cred.CredentialBlobSize / 2));
        } finally {
            CredFree(credPtr);
        }
    }
}
'@
}

$storePassword = [TcCredentialManager]::ReadGenericPassword($CredentialTarget)
$keyPassword = if ($KeyCredentialTarget) {
    [TcCredentialManager]::ReadGenericPassword($KeyCredentialTarget)
} else {
    $storePassword
}

$propsPath = Join-Path $root 'keystore.properties'
$hasProps = Test-Path -LiteralPath $propsPath -PathType Leaf

$envNames = @(
    'RELEASE_STORE_PASSWORD',
    'RELEASE_KEY_PASSWORD',
    'RELEASE_STORE_FILE',
    'RELEASE_KEY_ALIAS'
)
$savedEnv = @{}
foreach ($name in $envNames) {
    $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:RELEASE_STORE_PASSWORD = $storePassword
    $env:RELEASE_KEY_PASSWORD = $keyPassword

    if ($KeystoreFile) {
        $env:RELEASE_STORE_FILE = $KeystoreFile
    } elseif (!$hasProps -and !$env:RELEASE_STORE_FILE) {
        $env:RELEASE_STORE_FILE = 'trackercontrol.jks'
    }

    if ($KeyAlias) {
        $env:RELEASE_KEY_ALIAS = $KeyAlias
    } elseif (!$hasProps -and !$env:RELEASE_KEY_ALIAS) {
        $env:RELEASE_KEY_ALIAS = 'trackercontrol'
    }

    Push-Location $root
    try {
        & $gradlew @GradleArgs
        exit $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnv[$name], 'Process')
    }
}
