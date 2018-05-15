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

function Get-StreamDesignation {
[CmdletBinding()]
param(
  [Parameter(ValueFromPipeline = $true)] [object] $InputObject
)
  $StreamPrefix = "";
  if ($InputObject) {
    if ($InputObject.GetType().Name -eq 'VerboseRecord') {
      $StreamPrefix = 'VERBOSE: ';
    } elseif ($InputObject.GetType().Name -eq 'DebugRecord') {
      $StreamPrefix = 'DEBUG: ';
    } elseif ($InputObject.GetType().Name -eq 'WarningRecord') {
      $StreamPrefix = 'WARNING: ';
    }
  }
  $StreamPrefix
}

function Out-FileNoBom {
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true, Position=0)][AllowNull()] [System.IO.StreamWriter] $Writer,
  [Parameter(ValueFromPipeline = $true)]                [object] $InputObject
)
  Begin {
    # Create a buffer in case the input contains multiple objects that need to be flushed together, which is the case for formatted output
    [array]$Buffer = @();
  }

  Process {
    if ($Writer -and $Input -and $Input.Count -gt 0) {
      # Buffer the objects
      $Input | ForEach-Object {
        $Buffer += $_;
      }

      # Handle special internal format objects produced via format-* cmdlets
      if (!$InputObject.GetType().FullName.StartsWith('Microsoft.PowerShell.Commands.Internal.Format','CurrentCultureIgnoreCase') -or 
          ($InputObject.GetType().FullName.StartsWith('Microsoft.PowerShell.Commands.Internal.Format','CurrentCultureIgnoreCase') -and $InputObject.GetType().Name -ieq 'FormatEndData')) {

        # Try to force the output as text, but fallback on default if this is unsuccessful
        try {
          $Stream = $Buffer | Out-String -Stream -Width 192 -ErrorAction Stop;
        } catch {
          $Stream = $Buffer;
        }

        # Get any special stream designation that could have been lost
        $StreamDesignation = $InputObject | Get-StreamDesignation;

        # Flush the buffer
        $Stream | ForEach-Object {
          $Writer.Write( $StreamDesignation );
          $Writer.WriteLine( $_ ); 
        }
        $Buffer.Clear();
      }
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
    $errorCaught | Out-FileNoBom -Writer $LogWriter;
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