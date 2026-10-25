$token = $env:GITHUB_TOKEN
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$token"))
$proxy = "http://127.0.0.1:7890"

git -C D:\work\wxai add -A
git -C D:\work\wxai commit -m "feat: v2.5.1 - fix safety risk detection (remove anti-detect hook), fix memory reflection truncation, master auto-recognition and 12-tool preservation"
git -C D:\work\wxai tag -f v2.5.1
git -C D:\work\wxai -c http.proxy=$proxy -c https.proxy=$proxy -c "http.extraHeader=Authorization: Basic $auth" push origin main
git -C D:\work\wxai -c http.proxy=$proxy -c https.proxy=$proxy -c "http.extraHeader=Authorization: Basic $auth" push origin v2.5.1 --force
