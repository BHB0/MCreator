# publier.ps1 - Publie une version de ModLab sur GitHub
#
# Utilisation (dans PowerShell, depuis le dossier du projet) :
#     .\publier.ps1 1.0.0
#     .\publier.ps1 1.1.0 -Notes "Nouvelle page d'accueil"
#
# Le script : ecrit la version dans modlab.conf, cree le commit et le tag V<version>,
# envoie le tout sur GitHub puis cree la "Release" (si GitHub CLI est installe).
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = ""
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$num = $Version.TrimStart('v', 'V')
if ($num -notmatch '^\d+\.\d+\.\d+$') { throw "Version invalide. Utilise le format 1.0.0" }
$tag = "V$num"

if (-not (git config user.name) -or -not (git config user.email)) {
    throw 'Git ne sait pas qui tu es. Lance : git config --global user.name "TonPseudo"  puis  git config --global user.email "ton@email"'
}
if (git tag --list $tag) { throw "Le tag $tag existe deja. Choisis un numero de version plus grand." }

# 1. Ecrire la version dans modlab.conf (l'application la compare aux releases GitHub)
$conf = Join-Path $PSScriptRoot "src\main\resources\modlab.conf"
$text = [System.IO.File]::ReadAllText($conf)
$text = [regex]::Replace($text, '(?m)^version=[^\r\n]*', "version=$num")
[System.IO.File]::WriteAllText($conf, $text)

# 2. Commit
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    git commit -m "ModLab $tag"
    if ($LASTEXITCODE -ne 0) { throw "Le commit a echoue." }
} else {
    Write-Host "Rien de nouveau a enregistrer, on tag le dernier commit."
}

# 3. Tag + envoi sur GitHub
git tag -a $tag -m "ModLab $tag"
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
git push origin $branch
if ($LASTEXITCODE -ne 0) { throw "L'envoi de la branche a echoue (connexion GitHub ?)." }
git push origin $tag
if ($LASTEXITCODE -ne 0) { throw "L'envoi du tag a echoue." }

# 4. Release GitHub
$remote = (git remote get-url origin).Trim()
$repo = ($remote -replace '^.*github\.com[:/]', '') -replace '\.git$', ''
if ($Notes -eq "") { $Notes = "ModLab $tag" }

if (Get-Command gh -ErrorAction SilentlyContinue) {
    gh release create $tag --title "ModLab $tag" --notes $Notes --verify-tag
    if ($LASTEXITCODE -ne 0) { throw "La creation de la release a echoue. Essaie : gh auth login" }
    Write-Host "Release publiee : https://github.com/$repo/releases/tag/$tag"
} else {
    Write-Host ""
    Write-Host "Version $tag envoyee. Pour creer la release, ouvre ce lien puis clique sur 'Publish release' :"
    Write-Host "https://github.com/$repo/releases/new?tag=$tag&title=ModLab%20$tag"
    Write-Host "(Astuce : winget install GitHub.cli  puis  gh auth login  pour tout faire depuis ici.)"
}
