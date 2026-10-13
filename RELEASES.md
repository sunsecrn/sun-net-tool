# Downloads, distribuição e versões

As notas de evolução abaixo descrevem recursos do código e ensaios anteriores. Elas não significam que existam Releases publicadas. Consulte os [arquivos publicados no GitHub](https://github.com/sunsecrn/sun-net-tool/releases) para saber quais APKs estão efetivamente disponíveis.

> Exclusivamente para estudos. Uso por sua conta e risco. Recomendado apenas em ambientes e redes controladas, com autorização dos responsáveis. Exportações podem conter informações de rede e coordenadas: revise antes de compartilhar.

## Versão atual do código

**0.6.0**, `versionCode` 11, Android 10+, arm64-v8a e x86_64. A assinatura permanente está configurada no ambiente protegido; a verificação funcional é feita pelo workflow manual de teste de assinatura. A existência de secrets não comprova, sozinha, uma assinatura válida.

## Baixar e instalar, sem compilar

1. Abra [Releases](https://github.com/sunsecrn/sun-net-tool/releases) e escolha uma versão publicada. Se a lista estiver vazia, ainda não há APK de release disponível: a distribuição ainda não foi publicada. A configuração de assinatura precisa passar pelo teste protegido antes da primeira distribuição.
2. Baixe `sun-net-tool-vX.Y.Z-release.apk`, `SHA256SUMS` e `BUILD-INFO.txt` da mesma versão. O arquivo “Source code” não é instalável.
3. Confira o SHA-256 do APK contra a linha correspondente de `SHA256SUMS`: Linux `sha256sum arquivo.apk`, macOS `shasum -a 256 arquivo.apk` ou PowerShell `Get-FileHash arquivo.apk -Algorithm SHA256`. O checksum detecta alteração do arquivo, mas não substitui a confiança na origem. O certificado de assinatura fica em `BUILD-INFO.txt`.
4. No Android, abra o APK e autorize temporariamente a instalação por esse navegador/gerenciador quando solicitado. Depois desative essa permissão. Não ignore alertas de assinatura ou origem desconhecida.
5. Abra o app e conecte-se ao Wi-Fi autorizado. A captura solicita consentimento de VPN e se limita ao NetTool. Nenhuma varredura começa apenas ao abrir o app.

**Debug não é release.** [Actions](https://github.com/sunsecrn/sun-net-tool/actions/workflows/android-ci.yml) gera um artefato `debug-<commit>` com APK de desenvolvimento e checksum, retido por sete dias (download geralmente exige login). Builds de PR são código não confiável; não instale apenas por haver um artefato. A chave debug de runners efêmeros não é estável. Não há garantia de atualização sobre um debug anterior ou sobre release. O Android exige certificado compatível e `versionCode` adequado para atualizar preservando dados; não desinstale sem exportar o que precisar, pois a desinstalação apaga dados locais.

## Compilar o APK de distribuição

```sh
./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

O resultado `app/build/outputs/apk/release/app-release-unsigned.apk` não está assinado nem pronto para instalar. Use o processo protegido de assinatura, nunca a chave debug para distribuição. A chave privada e as senhas ficam fora do Git.

## Pipelines e assinatura

O [workflow de distribuição](.github/workflows/android-release.yml) é manual em `main`: valida uma tag existente e a versão, recompila sem segredos e assina em runner separado. Após as aprovações, cria somente um rascunho com APK, fonte correspondente, checksum e certificado. Não promove artefatos de PR e não publica automaticamente.

O [teste de assinatura](.github/workflows/verify-signing.yml) compila o código de `main`, usa o ambiente `release-signing`, verifica assinatura e fingerprint, e mantém o resultado como artefato temporário. Não cria tags, rascunhos ou Releases.

Veja [publicação segura](docs/RELEASING.md), [cadastro pessoal dos secrets](docs/SIGNING-SETUP.md) e [validação de empacotamento](docs/PACKAGING-VALIDATION.md).

## Evolução do aplicativo

## Debug opcional (0.2.0)

Em Configurações gerais > Registro debug, ative o diário local antes de reproduzir um problema. Ele registra eventos de interface, mudanças de rede, operações e resultados sanitizados. Exportar debug JSON salva via seletor Android. Começa desligado, tem limites e não registra digitação ou payloads. Veja [DEBUG.md](docs/DEBUG.md). Os relatórios históricos dos aparelhos ficam somente no ambiente local.

## Inventário e achados (0.3.0)

A navegação tem Explorar, Inventário, Achados, Captura e Sessão. Explorar mantém a lista de IPs e domínios com Mais recon. Inventário separa IPs, domínios e serviços. A porta aberta recebe tipo somente após evidência de protocolo. Achados apresenta respostas HTTP, cabeçalhos, referências e indícios mascarados. Exportações e histórico ficam em Sessão.

O perfil de 1.000 portas usa frequências da base importada, ordenação decrescente e desempate por porta crescente. Obtenha [nmap-services oficial](https://raw.githubusercontent.com/nmap/nmap/master/nmap-services), salve no aparelho e use Importar nmap-services. A [licença da base](https://nmap.org/npsl/) acompanha o Nmap; ela não faz parte do APK nem do ZIP deste projeto. Uma lista personalizada de até 1.000 portas continua disponível sem importação. A retomada usa os resultados anteriores do mesmo IP e conexão Wi-Fi; uma troca de rede exige novo teste.

O laboratório local reproduz HTTP, redirects, JS com dados sintéticos, HTTPS autoassinado e banner SSH. Consulte [LAB.md](docs/LAB.md). Os alvos do laboratório não são pré-carregados no inventário do aplicativo.

## Guia nos controles (0.3.2)

O primeiro acesso apresenta dez destaques sobre os controles reais: conexão Wi-Fi, descobrir hosts, testar portas, filtros de alvos, executar HTTP, iniciar captura, exportar JSON, segurança e possíveis CVEs, copiar sessão e configurações com debug e GPS. O Compose Showcase escurece o restante da tela e mantém uma explicação curta junto do controle. O guia troca de aba e rola até o alvo, com Próximo, Voltar, Pular e Concluir.

Tocar no controle destacado encerra o guia e executa a ação escolhida. Avançar pelos passos não inicia diagnósticos nem permissões. A etapa fica salva separadamente das sessões; encerrar o app permite retomar. Concluir ou pular encerra a apresentação automática. Menu > Modo tutor repete o guia e fica indisponível durante operações ou captura. A nova apresentação aparece uma vez também para quem já dispensou o antigo tutor textual.

## Investigação e debug local 0.4.0

A barra inferior usa Rede, Alvos, HTTP, Captura e Sessão, sem rótulos quebrados. Alvos reúne IPs, domínios, serviços, respostas HTTP, referências, indícios, DNS e sinais de portal. HTTP fica dedicado à execução das requisições e encaminha para os resultados em Alvos.

Menu > Configurações gerais > Abrir debug da sessão mostra tentativas, erros e contexto local com filtros, busca e detalhes. Tentativas negativas de portas são agrupadas por operação, alvo, resultado e causa em lotes de 100, preservando porta e tempo de cada tentativa. Até 4.000 grupos/eventos continuam sendo retidos; se esse limite for atingido, grupos negativos são removidos antes das demais evidências e o descarte fica contabilizado. O diário opcional de eventos do app mantém seus limites separados.

Rede diferencia o próprio aparelho, gateway e DNS. Usar gateway preenche o alvo; Investigar DNS da rede faz PTR do gateway, servidores DNS e até 32 IPs observados, além de consultar até oito sufixos informados pela rede. mDNS continua como complemento para anúncios locais. Rotas são mostradas como contexto, sem assumir descoberta de outras VLANs.

Menu > Configurações gerais > Perfil e cabeçalhos HTTP oferece perfil do app, perfil de navegador móvel e edição de User-Agent, Accept, Accept-Language e Cache-Control. O mesmo perfil atende HTTP e o gerador da captura. Não há cookies ou credenciais nesse perfil e ele não substitui autenticação nem resolve bloqueios TCP. O gerador da captura tem exemplos HTTP/HTTPS/DNS/TCP preenchidos, atalho para gateway e serviços HTTP observados; nada é enviado sem ação do usuário.

O laboratório inclui redirects de portal sintético, resposta 204 e eco dos cabeçalhos de compatibilidade. O login real de captive portal e o roteiro da rede conhecida continuam exigindo ensaio próprio.


### Análise de segurança 0.5.0

Alvos > Segurança reúne versões anunciadas, observações contextuais de headers e consulta manual de possíveis CVEs pela NVD. Cada achado preserva evidência e origem; correlação de versão não é confirmação de vulnerabilidade. A consulta externa envia somente CPE e mostra limites/falhas explicitamente. Veja docs/USO-RAPIDO.md e [validação de empacotamento](docs/PACKAGING-VALIDATION.md).


### Configurações gerais e localização 0.6.0

O menu de três pontos contém itens com ícones. Configurações gerais reúne HTTP, debug e localização opcional, desligada por padrão. Com permissão de localização precisa ou aproximada, o app registra coordenadas, horário, precisão e provedor enquanto estiver visível. Evidências recebem referência a posições de até 60 segundos. Durante a captura, as posições são identificadas para correlação por horário com o PCAP; o JSON da sessão carrega as anotações. Coleta em segundo plano não está habilitada. Sessão > Ver posições apresenta as medições registradas.
