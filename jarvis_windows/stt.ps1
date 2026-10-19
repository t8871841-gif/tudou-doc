[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Speech
$all=[System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers()
$ri=$all | Where-Object {$_.Culture.Name -eq $args[0]} | Select-Object -First 1
if(-not $ri){$ri=$all | Select-Object -First 1}
if(-not $ri){[Console]::Out.WriteLine("ERR:没有可用的语音识别器");exit 1}
$warned=$false
while($true){
  try{
    $e=New-Object System.Speech.Recognition.SpeechRecognitionEngine($ri)
    $e.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
    $e.SetInputToDefaultAudioDevice()
    [Console]::Out.WriteLine("READY:"+$ri.Culture.Name);[Console]::Out.Flush()
    $warned=$false
    while($true){
      $r=$e.Recognize([TimeSpan]::FromSeconds(5))
      if($r -and $r.Confidence -gt 0.5){[Console]::Out.WriteLine("T:"+$r.Text);[Console]::Out.Flush()}
    }
  }catch{
    if(-not $warned){[Console]::Out.WriteLine("ERR:未检测到麦克风，语音输入暂不可用，按 / 键打字");[Console]::Out.Flush();$warned=$true}
    Start-Sleep -Seconds 15
  }
}
