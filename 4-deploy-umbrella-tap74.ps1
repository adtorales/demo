param(
    [string]$UmbrellaPath = "",
    [string]$Namespace = "umbrella",
    [string]$ReleaseName = "umbrella",
    [string]$BaseValuesFile = "values-adopter-data-exchange.yaml",
    [string]$RegistryImage = "ghcr.io/adtorales/tap74-registry:latest",
    [string]$EdcDemoImage = "ghcr.io/adtorales/edc-demo:latest",
    [switch]$ResetVaultWebhookConfigs,
    [string]$GhcrUsername,
    [string]$GhcrPassword
)

$ErrorActionPreference = "Stop"

function Update-DataplaneSelectorUrl {
    param([string]$DeploymentName, [string]$ControlplaneServiceName)
    kubectl set env "deployment/$DeploymentName" `
        "EDC_DPF_SELECTOR_URL=http://$ControlplaneServiceName`:8083/control/v1/dataplanes" `
        -n $Namespace | Out-Null
}

function Add-InternalIngressDnsHost {
    param([string]$Hostname)

    $ingressIp = (& kubectl get service ingress-nginx-controller -n ingress-nginx -o jsonpath='{.spec.clusterIP}').Trim()
    if (-not $ingressIp) {
        throw "The ingress-nginx-controller ClusterIP could not be determined. Enable the Minikube ingress addon first."
    }

    $corefile = ((& kubectl get configmap coredns -n kube-system -o jsonpath='{.data.Corefile}') -join "`n")
    if ($corefile -match [regex]::Escape($Hostname)) {
        return
    }

    $entry = "           $ingressIp $Hostname`n"
    $updatedCorefile = [regex]::Replace($corefile, '(?m)^(\s*hosts \{\r?\n)', "`$1$entry", 1)
    if ($updatedCorefile -eq $corefile) {
        throw "Could not add $Hostname to the CoreDNS hosts section."
    }

    $coreDnsConfigMap = ((& kubectl get configmap coredns -n kube-system -o json) -join "`n") | ConvertFrom-Json
    $coreDnsConfigMap.data.Corefile = $updatedCorefile
    $coreDnsConfigMap | ConvertTo-Json -Depth 20 | & kubectl apply -f - | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to update CoreDNS with the internal mapping for $Hostname."
    }
}

$umbrellaRoot = if ($UmbrellaPath) { $UmbrellaPath } else { Join-Path $PSScriptRoot "..\tractus-x-umbrella" }
$UmbrellaPath = (Resolve-Path $umbrellaRoot).Path
$chartPath = Join-Path $UmbrellaPath "charts\umbrella"
$registryChartPath = Join-Path $PSScriptRoot "charts\tap74-registry"
$baseValuesPath = Join-Path $chartPath $BaseValuesFile
$overlayPath = Join-Path $PSScriptRoot "values\values-tap74-reconcile.yaml"
$resourcePath = Join-Path $PSScriptRoot "values\umbrella-tap74-local-resources.yaml"
$registryValuesPath = Join-Path $PSScriptRoot "values\tap74-registry.yaml"

@($chartPath, $registryChartPath, $baseValuesPath, $overlayPath, $resourcePath, $registryValuesPath) | ForEach-Object {
    if (-not (Test-Path $_)) { throw "Required deployment path not found: $_" }
}

if ($GhcrUsername -and $GhcrPassword) {
    & (Join-Path $PSScriptRoot "3-create-umbrella-registry-secret.ps1") `
        -Username $GhcrUsername -Password $GhcrPassword -Namespace $Namespace
}

if ($ResetVaultWebhookConfigs) {
    "umbrella-edc-dataconsumer-1-vault-agent-injector-cfg", "umbrella-edc-dataprovider-vault-agent-injector-cfg" |
        ForEach-Object { kubectl delete mutatingwebhookconfiguration $_ --ignore-not-found }
}

# The wallet resolves its public did:web hostname from inside the cluster. Map
# it to the in-cluster Ingress Service; Windows hosts entries only cover the host.
Add-InternalIngressDnsHost "ssi-dim-wallet-stub.tx.test"

$repos = @{
    "tractusx-dev" = "https://eclipse-tractusx.github.io/charts/dev"; "hashicorp" = "https://helm.releases.hashicorp.com"
    "bitnami" = "https://charts.bitnami.com/bitnami"; "runix" = "https://helm.runix.net"
    "opentelemetry" = "https://open-telemetry.github.io/opentelemetry-helm-charts"; "jaegertracing" = "https://jaegertracing.github.io/helm-charts"
    "prometheus-community" = "https://prometheus-community.github.io/helm-charts"; "grafana" = "https://grafana.github.io/helm-charts"; "jetstack" = "https://charts.jetstack.io"
}
$configured = helm repo list 2>$null
foreach ($name in $repos.Keys) { if (-not ($configured | Select-String -SimpleMatch $name)) { helm repo add $name $repos[$name] | Out-Null } }
helm repo update | Out-Null

foreach ($chartName in @("dataspace-connector-bundle", "digital-twin-bundle", "data-persistence-layer-bundle", "identity-and-trust-bundle", "tx-data-provider")) {
    $dependencyChart = Join-Path $UmbrellaPath "charts\$chartName"
    Write-Host "Building dependencies for $chartName..." -ForegroundColor Cyan
    helm dependency build $dependencyChart --skip-refresh
    if ($LASTEXITCODE -ne 0) {
        throw "Helm dependency build failed for $dependencyChart (exit code $LASTEXITCODE)."
    }
}

$separator = $RegistryImage.LastIndexOf(":")
$registryRepository = if ($separator -ge 0) { $RegistryImage.Substring(0, $separator) } else { $RegistryImage }
$registryTag = if ($separator -ge 0) { $RegistryImage.Substring($separator + 1) } else { "latest" }

Write-Host "Deploying TAP 7.4 Registry from GHCR..." -ForegroundColor Cyan
helm upgrade --install tap74-registry $registryChartPath --namespace $Namespace --create-namespace `
    -f $registryValuesPath --set "image.repository=$registryRepository" --set "image.tag=$registryTag"

Push-Location $chartPath
try {
    helm dependency build . --skip-refresh
    Write-Host "Deploying Umbrella with TAP 7.4 images from GHCR..." -ForegroundColor Cyan
    helm upgrade --install $ReleaseName . --namespace $Namespace --create-namespace `
        -f $baseValuesPath -f $overlayPath -f $resourcePath `
        --set "tap74Registry.enabled=false" `
        --set "identity-and-trust-bundle.ssi-dim-wallet-stub.wallet.nameSpace=$Namespace" --timeout 20m
} finally { Pop-Location }

Update-DataplaneSelectorUrl "$ReleaseName-dataconsumer-1-edc-dataplane" "$ReleaseName-dataconsumer-1-edc-controlplane"
Update-DataplaneSelectorUrl "$ReleaseName-dataprovider-edc-dataplane" "$ReleaseName-dataprovider-edc-controlplane"
Write-Host "Deployment complete. Verify with: kubectl get pods -n $Namespace" -ForegroundColor Green
