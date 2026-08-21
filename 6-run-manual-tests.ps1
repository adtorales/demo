param(
    [string]$ConsumerBaseUrl = "http://dataconsumer-1-controlplane.tx.test/management/v3",
    [string]$ProviderBaseUrl = "http://dataprovider-controlplane.tx.test/management/v3",
    [string]$ConsumerApiKey = "TEST1", [string]$ProviderApiKey = "TEST2",
    [string]$AssetId = "tap74-asset-001", [int]$ReconcilerIntervalSeconds = 30,
    [string]$Namespace = "umbrella", [switch]$CollectEvidence, [switch]$IncludeLockedRoomTest
)

# Runtime smoke checks plus S8b. Formal S1-S7 are GitOps/manual acceptance scenarios.
# Requires an active minikube tunnel, ingress, and *.tx.test hosts resolving to 127.0.0.1.
$ErrorActionPreference = "Stop"
$query = '{"@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},"@type":"QuerySpec","offset":0,"limit":50}'
$result = [ordered]@{}
$evidence = if ($CollectEvidence) { Join-Path $PSScriptRoot ("evidence-" + (Get-Date -Format "yyyyMMdd-HHmmss")) }
if ($evidence) { New-Item -ItemType Directory -Force $evidence | Out-Null }

function Call-Edc([string]$Url, [string]$Key, [string]$Path, [string]$Body = $query) {
    Invoke-RestMethod -Method Post -Uri "$Url$Path" -Headers @{ "X-Api-Key" = $Key; "Content-Type" = "application/json" } -Body $Body
}
function Get-EdcItems($Response) {
    if ($null -ne $Response.value) {
        return @($Response.value)
    }
    return @($Response)
}
function Save-Evidence([string]$Name, $Value) { if ($evidence) { $Value | ConvertTo-Json -Depth 100 | Set-Content (Join-Path $evidence "$Name.json") } }
function Run-Check([string]$Id, [scriptblock]$Action) {
    try { $ok = & $Action; $result[$Id] = [bool]$ok; Write-Host "$Id : $(if ($ok) {'PASSED'} else {'FAILED'})" -ForegroundColor $(if ($ok) {'Green'} else {'Red'}) }
    catch { $result[$Id] = $false; Write-Host "$Id : FAILED - $($_.Exception.Message)" -ForegroundColor Red }
}

Write-Host "TAP 7.4 runtime checks; S1-S7 must be performed through their documented GitOps steps." -ForegroundColor Cyan
Run-Check "B1-management-api" {
    $consumer = Call-Edc $ConsumerBaseUrl $ConsumerApiKey "/assets/request"; $provider = Call-Edc $ProviderBaseUrl $ProviderApiKey "/assets/request"
    Save-Evidence "b1-consumer-assets" $consumer; Save-Evidence "b1-provider-assets" $provider; $true
}
Run-Check "B2-reconciled-policies" {
    $consumer = @(Get-EdcItems (Call-Edc $ConsumerBaseUrl $ConsumerApiKey "/policydefinitions/request"))
    $provider = @(Get-EdcItems (Call-Edc $ProviderBaseUrl $ProviderApiKey "/policydefinitions/request"))
    Save-Evidence "b2-consumer-policies" $consumer; Save-Evidence "b2-provider-policies" $provider
    $left = @($consumer | Sort-Object { $_.'@id' } | ForEach-Object { "$($_.'@id')|$($_.privateProperties.'xregistry:contentHash')" }) -join "`n"
    $right = @($provider | Sort-Object { $_.'@id' } | ForEach-Object { "$($_.'@id')|$($_.privateProperties.'xregistry:contentHash')" }) -join "`n"
    $consumer.Count -gt 0 -and $left -eq $right
}
Run-Check "B3-provider-seeded" {
    $assets = @(Get-EdcItems (Call-Edc $ProviderBaseUrl $ProviderApiKey "/assets/request"))
    $contracts = @(Get-EdcItems (Call-Edc $ProviderBaseUrl $ProviderApiKey "/contractdefinitions/request"))
    Save-Evidence "b3-provider-assets" $assets; Save-Evidence "b3-provider-contractdefinitions" $contracts
    (@($assets | ForEach-Object { $_.'@id' }) -contains $AssetId) -and $contracts.Count -gt 0
}

if ($IncludeLockedRoomTest) {
    Run-Check "S8b-locked-room" {
        $rogueId = "rogue-manual-test-$(Get-Random -Maximum 999999)"
        $rogue = @{ "@context" = @{ "@vocab" = "https://w3id.org/edc/v0.0.1/ns/"; odrl = "http://www.w3.org/ns/odrl/2/" }; "@id" = $rogueId; "@type" = "PolicyDefinition"; policy = @{ "@id" = "$rogueId-policy"; "@type" = "odrl:Set"; permission = @(@{ action = "use"; constraint = @(@{ leftOperand = "FrameworkAgreement"; operator = "eq"; rightOperand = "CX-2026" }) }) } } | ConvertTo-Json -Depth 20
        Call-Edc $ConsumerBaseUrl $ConsumerApiKey "/policydefinitions" $rogue | Out-Null
        Start-Sleep -Seconds ($ReconcilerIntervalSeconds + 15)
        $policies = @(Get-EdcItems (Call-Edc $ConsumerBaseUrl $ConsumerApiKey "/policydefinitions/request"))
        Save-Evidence "s8b-policies-after-sweep" $policies
        -not ($policies.'@id' -contains $rogueId)
    }
}

if ($evidence) {
    kubectl get pods -n $Namespace -o wide | Set-Content (Join-Path $evidence "pods.txt")
    kubectl logs -n $Namespace deployment/tap74-registry --tail=200 | Set-Content (Join-Path $evidence "registry.log")
    Write-Host "Evidence: $evidence" -ForegroundColor Cyan
}
if ($result.Values -contains $false) { exit 1 }
