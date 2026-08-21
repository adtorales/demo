param(
    [Parameter(Mandatory = $true)] [string]$Username,
    [Parameter(Mandatory = $true)] [string]$Password,
    [string]$Namespace = "umbrella",
    [string]$SecretName = "tap74-registry-oci-creds"
)

$ErrorActionPreference = "Stop"
if (-not (kubectl get namespace $Namespace --ignore-not-found -o name)) {
    kubectl create namespace $Namespace
}

kubectl create secret generic $SecretName --namespace $Namespace `
    --from-literal=username=$Username --from-literal=password=$Password `
    --dry-run=client -o yaml | kubectl apply -f -
Write-Host "Secret $SecretName applied in namespace $Namespace." -ForegroundColor Green
