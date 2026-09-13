Add-Type -AssemblyName System.Drawing

# ASCII-only script: the CJK glyph is referenced by code point to avoid
# any script-encoding pitfalls (a mis-decoded multibyte char can swallow quotes).
$WORD = [string][char]0x8BCD   # U+8BCD = "ci" (the character used for the lyrics button)

function Get-GlyphPathData([string]$text, [double]$size, [double]$margin) {
    $family = New-Object System.Drawing.FontFamily("Microsoft YaHei")
    $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $fmt = [System.Drawing.StringFormat]::GenericDefault
    $gp.AddString($text, $family, [int][System.Drawing.FontStyle]::Bold, 100.0,
        (New-Object System.Drawing.PointF(0, 0)), $fmt)
    $pts = $gp.PathPoints
    $types = $gp.PathTypes
    $b = $gp.GetBounds()
    $inner = $size - 2 * $margin
    $scale = $inner / [Math]::Max($b.Width, $b.Height)
    $dx = ($size - $b.Width * $scale) / 2.0 - $b.X * $scale
    $dy = ($size - $b.Height * $scale) / 2.0 - $b.Y * $scale
    $sb = New-Object System.Text.StringBuilder
    $i = 0
    while ($i -lt $pts.Count) {
        $t = $types[$i] -band 0x07
        $close = (($types[$i] -band 0x80) -ne 0)
        if ($t -eq 0 -or $t -eq 1) {
            $cmd = 'M'
            if ($t -eq 1) { $cmd = 'L' }
            $x = [Math]::Round($pts[$i].X * $scale + $dx, 2)
            $y = [Math]::Round($pts[$i].Y * $scale + $dy, 2)
            [void]$sb.Append($cmd + $x + ' ' + $y + ' ')
            $i++
        } elseif ($t -eq 3) {
            if ($i + 2 -ge $pts.Count) { break }
            $s = ''
            foreach ($k in 0..2) {
                $x = [Math]::Round($pts[$i + $k].X * $scale + $dx, 2)
                $y = [Math]::Round($pts[$i + $k].Y * $scale + $dy, 2)
                $s += $x.ToString() + ' ' + $y.ToString() + ' '
            }
            [void]$sb.Append('C' + $s.Trim() + ' ')
            $i += 3
        } else {
            $i++
            continue
        }
        if ($close) { [void]$sb.Append('Z ') }
    }
    $gp.Dispose()
    $family.Dispose()
    return $sb.ToString().Trim()
}

$data = Get-GlyphPathData $WORD 24.0 1.2
Write-Host ('pathData length: ' + $data.Length)

$nl = "`n"
$head = '<?xml version="1.0" encoding="utf-8"?>' + $nl +
    '<!-- Notification lyrics-toggle icon: the CJK glyph U+8BCD rendered from the system font outline. -->' + $nl +
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"' + $nl +
    '    android:width="24dp"' + $nl +
    '    android:height="24dp"' + $nl +
    '    android:viewportWidth="24"' + $nl +
    '    android:viewportHeight="24">' + $nl
$glyph = '    <path' + $nl +
    '        android:fillColor="#FFFFFFFF"' + $nl +
    '        android:pathData="' + $data + '" />'
$tail = $nl + '</vector>' + $nl

$check = $nl + $nl +
    '    <!-- check mark: floating lyrics enabled -->' + $nl +
    '    <path' + $nl +
    '        android:fillColor="#00000000"' + $nl +
    '        android:strokeColor="#FFFFFFFF"' + $nl +
    '        android:strokeWidth="1.7"' + $nl +
    '        android:strokeLineCap="round"' + $nl +
    '        android:strokeLineJoin="round"' + $nl +
    '        android:pathData="M14.6 19.2 L16.6 21.2 L21.2 16" />'

$lock = $nl + $nl +
    '    <!-- padlock: floating lyrics locked -->' + $nl +
    '    <path' + $nl +
    '        android:fillColor="#FFFFFFFF"' + $nl +
    '        android:pathData="M14.9 18.6 L21.1 18.6 L21.1 22.1 L14.9 22.1 Z" />' + $nl +
    '    <path' + $nl +
    '        android:fillColor="#00000000"' + $nl +
    '        android:strokeColor="#FFFFFFFF"' + $nl +
    '        android:strokeWidth="1.3"' + $nl +
    '        android:pathData="M16.3 18.6 L16.3 16.8 A1.7 1.7 0 0 1 19.7 16.8 L19.7 18.6" />'

$dir = 'core/player/src/main/res/drawable'
$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Resolve-Path $dir).Path + '\ic_lyrics_word.xml', $head + $glyph + $tail, $enc)
[System.IO.File]::WriteAllText((Resolve-Path $dir).Path + '\ic_lyrics_word_active.xml', $head + $glyph + $check + $tail, $enc)
[System.IO.File]::WriteAllText((Resolve-Path $dir).Path + '\ic_lyrics_word_locked.xml', $head + $glyph + $lock + $tail, $enc)
Get-ChildItem "$dir/ic_lyrics_word*.xml" | Select-Object Name, Length
