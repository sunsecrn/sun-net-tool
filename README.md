![Sun NetTool — diagnóstico de redes Wi-Fi para Android](docs/branding/readme-banner.png)

# Sun NetTool Android

MVP nativo em Kotlin e Jetpack Compose para diagnóstico e investigação de redes Wi-Fi, sem root, com evidências locais e ações iniciadas pelo usuário. Compatível com **Android 10+**, **arm64-v8a e x86_64**.

> **Exclusivamente para estudos. Uso por sua conta e risco. Recomendado apenas em ambientes e redes controladas, com autorização explícita dos responsáveis.** Não execute descoberta, testes de portas ou captura em redes de terceiros sem autorização.

[Guia de uso](docs/USO-RAPIDO.md) · [Downloads, instalação e versões](RELEASES.md) · [Captura](CAPTURE.md) · [Componentes de terceiros](THIRD_PARTY_NOTICES.md)

## Funcionalidades reais

- Contexto Wi-Fi, endereços, rotas, gateway e DNS; descoberta limitada e testes TCP explícitos.
- Consultas DNS e descoberta de serviços; inventário com origem das evidências.
- HTTP GET com redirects limitados, headers, TLS validado, referências HTML/JavaScript e indícios mascarados.
- Versões anunciadas, observações de segurança e consulta manual de possíveis CVEs por CPE na NVD. Correspondência não comprova vulnerabilidade.
- Captura VPN apenas do próprio app, encaminhamento TCP/UDP, PCAP, busca textual/RE2 e exportação explícita.
- Sessões JSON/CSV, retenção limitada, diário debug opcional e guia nos controles.
- Localização opcional do aparelho durante o uso em primeiro plano, mediante ativação e permissão; exportações podem incluir coordenadas. Isso não localiza o serviço remoto.

Sessões permanecem no armazenamento privado do app até exportação explícita. Diagnósticos contatam os alvos escolhidos; a consulta manual à NVD envia CPE ao serviço externo. PCAP pode conter payload e dados sensíveis: não publique exportações.

## Prints reais do Android

Capturas de versões anteriores (0.3.2 e 0.4.0), selecionadas e revisadas sem endereços de rede, alvos, identificadores de sessão ou credenciais visíveis. A navegação da versão atual pode diferir.

| Guia de captura | Configuração HTTP |
| --- | --- |
| <img src="docs/screenshots/a17-0.3.2-guide.png" width="280" alt="Guia da captura no Android, sem dados de rede"> | <img src="docs/screenshots/s23-0.4.0-profile.png" width="280" alt="Configuração de cabeçalhos HTTP, sem alvos ou credenciais"> |

## Compilar

Requer JDK 17, Android SDK 35, Build Tools 35.0.0, NDK 27.2.12479018 e CMake 3.22.1. Configure `ANDROID_HOME` ou `sdk.dir` em `local.properties` (não versionado).

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No Windows, use `gradlew.bat`. O wrapper baixa Gradle 8.9 e verifica SHA-256. O debug usa a chave de desenvolvimento local do Android; não a publique nem a use como chave de distribuição.

## Testes e integração contínua

O [CI](.github/workflows/android-ci.yml) executa testes unitários, Android lint e build debug em PRs e em `main`, com permissões de leitura e sem segredos de assinatura. Consulte as [execuções no GitHub](https://github.com/sunsecrn/sun-net-tool/actions).

Os testes instrumentados exigem um aparelho e um ambiente explicitamente autorizado. Eles não fazem parte do CI hospedado; esta etapa de empacotamento não executa diagnósticos de rede contra terceiros. O [teste nativo](tests/native/README.md) usa loopback.

## Fluxo de uso

1. Confira o contexto Wi-Fi em **Rede** e selecione somente alvos autorizados.
2. Execute descoberta, DNS ou testes de portas e acompanhe cobertura, erros e cancelamentos.
3. Em **HTTP**, faça uma requisição e consulte as evidências em **Alvos**. Indícios e possíveis CVEs não confirmam vulnerabilidades.
4. Se necessário, ative a captura do próprio NetTool com consentimento Android; pare antes de buscar ou exportar PCAP.
5. Em **Sessão**, revise resultados e exporte JSON/CSV ou copie a investigação. Credenciais conhecidas são mascaradas; coordenadas registradas continuam presentes nas exportações.

O guia inicial destaca dez controles. **Menu > Configurações gerais** reúne perfil HTTP, debug e localização opcional, desligada por padrão. Consulte o [guia completo](docs/USO-RAPIDO.md) para perfis de portas, retenção e limites.

## Limitações explícitas

- A captura é real, com encaminhamento TCP/UDP zdtun; está restrita ao próprio aplicativo. Com captura ativa, os diagnósticos iniciados pelo app usam a VPN; a saída do encaminhador permanece vinculada ao Wi-Fi físico.
- Não há MITM, leitura universal de HTTPS, captura de outros aparelhos, raw scans, root, fingerprinting de SO, ICMP/traceroute nem enumeração completa de VLANs/IPv6.
- O teste de portal registra status e redirects de uma consulta HTTP, sem confirmar sozinho autenticação obrigatória. O login fica no Android/navegador. Cookies reutilizados, comparação automática antes/depois, SPAs e correlação DNS temporal de fluxos não são implementados.
- Não há MAC/fabricante nem banco local de vulnerabilidades. A consulta manual à NVD sugere correspondências por CPE, sem comprovar falhas. Chave pública, token e recurso público não provam falha.
- O cliente HTTP faz GET sem credenciais configuráveis. Redirecionamento HTTPS para HTTP é registrado sem ser seguido. TLS inválido produz diagnóstico, nunca bypass.
- PCAP contém payload original e pode conter informações sensíveis. Só sua exportação explícita entrega esses bytes; relatórios textuais mascaram credenciais conhecidas, queries e amostras de padrões sensíveis.
- Histórico não é banco relacional completo. Evidências têm parentId/origem, entidades são derivadas de registros. DNS-SD registra até 40 anúncios, resolve apenas um simultaneamente e pode deixar anúncios sem resolução.
- Rotação mantém o modelo; saída definitiva da atividade interrompe operações. Captura segue em foreground até parada, limite, revogação ou perda do Wi-Fi. Estado visual de capturas antigas não é restaurado após morte do processo; arquivos privados permanecem sujeitos à retenção.

Veja [CAPTURE.md](CAPTURE.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e [teste nativo](tests/native/README.md) para motor, licença, reconstrução e limites de captura.

## Referências técnicas

- [Android Network](https://developer.android.com/reference/android/net/Network)
- [DnsResolver](https://developer.android.com/reference/android/net/DnsResolver)
- [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)
- [VpnService](https://developer.android.com/develop/connectivity/vpn)
- [zdtun](https://github.com/emanuele-f/zdtun)

## Créditos

Agradecimento à [comunidade Sunsec](https://sunsec.net), que ajuda nas pesquisas para o desenvolvimento destes aplicativos. O banner é uma ilustração do projeto, sem dados de redes reais; a identidade Sunsec original permanece preservada em [branding](docs/branding/README.md).
