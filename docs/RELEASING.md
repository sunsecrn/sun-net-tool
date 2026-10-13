# Publicação segura de APKs

## Estado inicial

Repositório solicitado: `sunsecrn/sun-net-tool` (público). Não havia Git ou remoto no diretório original. A chave de release, as senhas e o certificado esperado **não foram gerados nem configurados** nesta preparação. A primeira Release depende da aprovação e configuração de assinatura pelo mantenedor. APK debug não estabelece identidade de assinatura para futuras atualizações.

## Modelo de confiança

- `android-ci.yml`: `pull_request`, push em `main` e execução manual; somente `contents: read`. Sem `pull_request_target`, segredos, runner próprio ou cache compartilhado. Publica apenas APK debug e checksum, por sete dias.
- `android-release.yml`: somente execução manual a partir de `main`. Recebe tag `vX.Y.Z` já existente, exige ancestralidade em `main` e igualdade com `versionName` do APK. Recompila e testa o código dessa tag, sem segredos.
- Assinatura em runner separado, sem checkout ou Gradle. Recebe somente artefatos da mesma execução; não baixa artefatos de PR. Keystore temporário com permissões restritas, senhas via ambiente, limpeza ao terminar. `apksigner verify` e comparação obrigatória com fingerprint esperado; `zipalign` verifica alinhamento de 16 KiB.
- Apenas o job final tem `contents: write`. Não executa código do repositório; verifica checksums e cria **rascunho**, sem sobrescrever Release existente nem publicar automaticamente.
- Actions fixadas por SHA completo, conferidos contra tags dos repositórios oficiais: checkout v4.2.2, setup-java v4.7.1, upload-artifact v4.6.2 e download-artifact v4.3.0; setup-android v3.2.2 do projeto android-actions. Atualizações devem passar por revisão. O wrapper fixa e verifica o SHA-256 do Gradle.
- Builds usam dependências Maven versionadas, mas não têm lockfile/verificação de todos os artefatos transitivos; não há promessa de reprodução bit a bit ou cadeia de fornecimento inteiramente hermética.

## Configuração pelo mantenedor

Para criação local e cadastro pessoal dos segredos, siga [SIGNING-SETUP.md](SIGNING-SETUP.md). O script de criação não transmite segredos; o cadastro no GitHub é realizado pelo próprio mantenedor.

1. Proteja `main` e tags de versão, restrinja quem pode executar/alterar workflows e exija revisão. Não aprove tags de código não revisado.
2. Crie os environments `release-signing` e `release-publication`, com aprovadores obrigatórios, proibição de autoaprovação quando disponível e restrição a `main`. A mera referência no YAML não configura essas proteções: sem configuração, GitHub pode criar environments desprotegidos. Confirme suporte no plano da organização.
3. Após aprovação específica, use uma chave de distribuição sob controle do mantenedor, com backup seguro. Não envie a chave ou senhas em issues, chat, Git, logs ou artefatos. Nunca use a chave debug como chave de release.
4. No environment `release-signing`, configure os secrets:

| Secret | Conteúdo |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore codificado em base64 (base64 não é criptografia) |
| `ANDROID_KEYSTORE_PASSWORD` | Senha do keystore |
| `ANDROID_KEY_ALIAS` | Alias da chave |
| `ANDROID_KEY_PASSWORD` | Senha da chave |

Configure também a variável `ANDROID_SIGNING_CERT_SHA256`: fingerprint SHA-256 do certificado, exatamente 64 caracteres hexadecimais sem dois-pontos. Obtenha-o por canal confiável do keystore aprovado e compare com `apksigner verify --print-certs` de um APK assinado. Não derive automaticamente o valor esperado do APK que será verificado.

5. Antes de distribuir atualização, compare o certificado com a versão já instalada e incremente `versionCode`. A manutenção da mesma chave é necessária, mas não basta para prometer uma atualização validada: teste em aparelho autorizado preservando os dados.

## Primeira versão e versões seguintes

Depois dos checks verdes e revisão do conteúdo público:

```sh
# Exemplo: usar somente se versionName for 0.6.0 e a tag ainda não existir.
git tag -a v0.6.0 -m 'Sun NetTool 0.6.0'
git push origin v0.6.0
gh workflow run android-release.yml --ref main -f tag=v0.6.0
```

Aprove os environments somente após conferir commit, tag, checks e origem dos artefatos. O rascunho terá APK release assinado, `SHA256SUMS`, `BUILD-INFO.txt` (commit e certificado) e arquivo de fonte correspondente. O código nativo e licenças acompanham a fonte; consulte [avisos](../THIRD_PARTY_NOTICES.md) e [captura](../CAPTURE.md).

Baixe o rascunho, confira checksum, assinatura e instalação/atualização em ambiente autorizado. Revise notas, avisos de uso e os quatro anexos. A publicação pública do rascunho é uma decisão explícita posterior do mantenedor. Se uma execução falhar após criar o rascunho, inspecione-o antes de tentar novamente; o workflow não sobrescreve anexos existentes.

## O que não publicar

`local-evidence/`, `dist/`, sessões JSON/CSV, PCAP, logs, dados de usuários, serial de aparelhos, capturas de tela não revisadas, caches, keystores e credenciais. O `.gitignore` reduz acidentes, mas não detecta todos os nomes possíveis de exportações JSON; revise `git diff --cached` e a lista completa de arquivos em cada publicação. Não use `git add -f` para contornar essas exclusões.

Os dois prints permitidos em `.gitignore` foram revisados visualmente; os demais continuam privados. Não adicione um novo print sem conferir também o fundo, diálogos, campos e metadados.

Referências: [segurança de Actions](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions), [environments](https://docs.github.com/en/actions/deployment/targeting-different-environments/managing-environments-for-deployment), [apksigner](https://developer.android.com/tools/apksigner).
