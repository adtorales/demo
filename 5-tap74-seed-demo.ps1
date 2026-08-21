param(
    [string]$ProviderBaseUrl = "http://dataprovider-controlplane.tx.test/management/v3",
    [string]$ConsumerBaseUrl = "http://dataconsumer-1-controlplane.tx.test/management/v3",
    [string]$ProviderApiKey = "TEST2", [string]$ConsumerApiKey = "TEST1",
    [string]$AssetId = "tap74-asset-001", [string]$ContractDefinitionId = "tap74-contract-definition-001",
    [string]$AccessPolicyId = "cx/membership-access/1.0", [string]$ContractPolicyId = "cx/usage-framework/1.0",
    [string]$CounterPartyAddress = "http://umbrella-dataprovider-edc-controlplane:8084/api/v1/dsp/2025-1",
    [string]$CounterPartyId = "BPNL00000003AYRE", [string]$Protocol = "dataspace-protocol-http:2025-1",
    [string]$AssetBaseUrl = "http://dataprovider-submodelserver.tx.test/api/shell-descriptors", [switch]$SkipCatalogRequest
)

$headersProvider = @{ "X-Api-Key" = $ProviderApiKey; "Content-Type" = "application/json" }
$headersConsumer = @{ "X-Api-Key" = $ConsumerApiKey; "Content-Type" = "application/json" }
$ErrorActionPreference = "Stop"
$asset = @{ "@context" = @{ "@vocab" = "https://w3id.org/edc/v0.0.1/ns/" }; "@id" = $AssetId; "@type" = "Asset"; properties = @{ name = "TAP 7.4 Demo Asset"; description = "Asset used by the GitOps Policy Flow demonstrator"; contenttype = "application/json" }; dataAddress = @{ "@type" = "DataAddress"; type = "HttpData"; baseUrl = $AssetBaseUrl } } | ConvertTo-Json -Depth 10
$contract = @{ "@context" = @{ "@vocab" = "https://w3id.org/edc/v0.0.1/ns/" }; "@id" = $ContractDefinitionId; "@type" = "ContractDefinition"; accessPolicyId = $AccessPolicyId; contractPolicyId = $ContractPolicyId; assetsSelector = @(@{ "@type" = "CriterionDto"; operandLeft = "https://w3id.org/edc/v0.0.1/ns/id"; operator = "="; operandRight = $AssetId }) } | ConvertTo-Json -Depth 10

function Invoke-CreateOrKeep([string]$Uri, [hashtable]$Headers, [string]$Body, [string]$Description) {
    try {
        Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers -Body $Body | Out-Null
        Write-Host "Created $Description." -ForegroundColor Green
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 409) {
            Write-Host "$Description already exists; keeping it." -ForegroundColor Yellow
            return
        }
        throw
    }
}

Invoke-CreateOrKeep "$ProviderBaseUrl/assets" $headersProvider $asset "asset '$AssetId'"
Invoke-CreateOrKeep "$ProviderBaseUrl/contractdefinitions" $headersProvider $contract "contract definition '$ContractDefinitionId'"
if (-not $SkipCatalogRequest) {
    $request = @{ "@context" = @(@{ "@vocab" = "https://w3id.org/edc/v0.0.1/ns/" }); "@type" = "CatalogRequest"; counterPartyAddress = $CounterPartyAddress; counterPartyId = $CounterPartyId; protocol = $Protocol; querySpec = @{ offset = 0; limit = 50; sortOrder = "DESC"; sortField = "fieldName"; filterExpression = @() } } | ConvertTo-Json -Depth 10
    Invoke-RestMethod -Method Post -Uri "$ConsumerBaseUrl/catalog/request" -Headers $headersConsumer -Body $request | ConvertTo-Json -Depth 20
}
