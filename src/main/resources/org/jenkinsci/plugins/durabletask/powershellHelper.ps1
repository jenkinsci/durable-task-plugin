# By default PowerShell adds a byte order mark (BOM) to the beginning of a file when using Out-File with a unicode encoding such as UTF8.
# This causes the Jenkins output to contain bogus characters because Java does not handle the BOM characters by default.
# This code mimics Out-File, but does not write a BOM.  Hopefully PowerShell will provide a non-BOM option for writing files in future releases.
        
function New-StreamWriter {
[CmdletBinding()]
param (
  [Parameter(Mandatory=$true)] [string] $FilePath,
  [Parameter(Mandatory=$true)] [System.Text.Encoding] $Encoding
)
  [string]$FullFilePath = [IO.Path]::GetFullPath( $FilePath );
  [System.IO.StreamWriter]$writer = New-Object System.IO.StreamWriter( $FullFilePath, $true, $Encoding );
  $writer.AutoFlush = $true;
  return $writer;
}

function Out-FileNoBom {
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true, Position=0)][AllowNull()] [System.IO.StreamWriter] $Writer,
  [Parameter(ValueFromPipeline = $true)]                [object] $InputObject
)
  Process {
    if ($Writer) {
      $Input | Out-String -Stream -Width 192 | ForEach-Object { $Writer.WriteLine( $_ ); }
    } else {
      $Input;
    }
  }
}

function Execute-AndWriteOutput {
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]  [string]$MainScript,
  [Parameter(Mandatory=$false)] [string]$OutputFile,
  [Parameter(Mandatory=$true)]  [string]$LogFile,
  [Parameter(Mandatory=$true)]  [string]$ResultFile,
  [Parameter(Mandatory=$false)] [switch]$CaptureOutput
)
  try {
    $exitCode = 0;
    $errorCaught = $null;
    [System.Text.Encoding] $encoding = New-Object System.Text.UTF8Encoding( $false );
    [System.Console]::OutputEncoding = [System.Console]::InputEncoding = $encoding;
    [System.IO.Directory]::SetCurrentDirectory( $PWD );
    $null = New-Item $LogFile -ItemType File -Force;
    [System.IO.StreamWriter] $LogWriter = New-StreamWriter -FilePath $LogFile -Encoding $encoding;
    $OutputWriter = $null;
    if ($CaptureOutput -eq $true) {
      $null = New-Item $OutputFile -ItemType File -Force;
      [System.IO.StreamWriter]$OutputWriter = New-StreamWriter -FilePath $OutputFile -Encoding $encoding;
    }
    & { & $MainScript | Out-FileNoBom -Writer $OutputWriter } *>&1 | Out-FileNoBom -Writer $LogWriter;
  } catch {
    $errorCaught = $_;
    $errorCaught | Out-String -Width 192 | Out-FileNoBom -Writer $LogWriter;
  } finally {
    if ($LASTEXITCODE -ne $null) {
      if ($LASTEXITCODE -eq 0 -and $errorCaught -ne $null) {
        $exitCode = 1;
      } else {
        $exitCode = $LASTEXITCODE;
      }
    } elseif ($errorCaught -ne $null) {
      $exitCode = 1;
    }
    $exitCode | Out-File -FilePath $ResultFile -Encoding ASCII;
    if ($CaptureOutput -eq $true -and !(Test-Path $OutputFile)) {
      $null = New-Item $OutputFile -ItemType File -Force;
    }
    if (!(Test-Path $LogFile)) {
      $null = New-Item $LogFile -ItemType File -Force;
    }
    if ($CaptureOutput -eq $true -and $OutputWriter -ne $null) {
      $OutputWriter.Flush();
      $OutputWriter.Dispose();
    }
    if ($LogWriter -ne $null) {
      $LogWriter.Flush();
      $LogWriter.Dispose();
    }
    exit $exitCode;
  }
}