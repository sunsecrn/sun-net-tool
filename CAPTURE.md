# Captura local do MVP

O APK implementa uma VPN local, com consentimento do Android, que recebe pacotes do TUN e encaminha TCP e UDP por sockets reais. Respostas retornam ao TUN. Somente o próprio NetTool entra na VPN neste MVP. Os demais aplicativos ficam fora da captura. Na versão 0.4.0, diagnósticos iniciados durante a captura selecionam a Network VPN. O encaminhador continua saindo pelo Wi-Fi físico com sockets protegidos contra loop. O gerador oferece HTTP, HTTPS, DNS e TCP com alvos prontos ou editáveis.

O encaminhamento usa zdtun, com sockets protegidos por VpnService.protect e vinculados ao handle da rede Wi-Fi física com android_setsocknetwork antes de connect/send. Se qualquer proteção ou bind falhar, o socket é recusado. A perda do Wi-Fi encerra a VPN. Não há fallback intencional ao celular. Outra VPN substitui esta, e revogação encerra o serviço. A captura não descriptografa HTTPS e não intercepta TLS.

## Limites e evidências

- Máximo de 10 minutos, 25 MiB por PCAP, 256 conexões no motor e 512 fluxos no painel.
- IPv4 e IPv6 TCP/UDP são encaminhados. ICMP e outros protocolos não são encaminhados pelo MVP. Não há promessa de funcionamento de todos os protocolos ou pacotes fragmentados.
- Os metadados reconhecem cabeçalhos básicos TCP/UDP IPv4/IPv6. Extensões IPv6 e fragmentos não iniciais não viram registros de fluxo.
- Pacotes de protocolos não suportados e fragmentos reconhecidos têm contador separado. Erros contabilizam falhas de encaminhamento e escrita no TUN; não são uma medição completa de perdas da rede. Registros omitidos indicam pacotes de fluxos além do limite da tabela.
- UID e pacotes associados são obtidos quando o Android permite. UID desconhecido é válido; vários pacotes podem compartilhar UID. Visibilidade de pacotes pode ser restrita pelo Android.
- PCAP clássico usa LINKTYPE_RAW (101), com pacotes em ambas as direções e timestamp em microssegundos a partir do relógio de parede em milissegundos.
- PCAP inclui bytes originais e pode conter dados sensíveis de tráfego claro. JSON/CSV contêm apenas metadados. Exportar PCAP requer ação explícita, não é relatório mascarado.
- Até três grupos PCAP/JSON/CSV são mantidos. Na próxima captura, arquivos com mais de 24 horas são removidos. Não é um agendador de expiração em segundo plano.
- DNS visível não é correlacionado automaticamente neste MVP. DoH/TLS permanece criptografado. IP compartilhado não identifica um hostname com certeza.

## Dependência e licença

Fonte: https://github.com/emanuele-f/zdtun

Commit vendorizado: `80b7941d4e685d8354346820878eaec0e35b6b1e`.

zdtun declara LGPL-3.0-or-later em seus cabeçalhos. A biblioteca é compilada em `libzdtun.so`, separada da ponte `libnettool_capture.so`. O código correspondente está em `app/src/main/cpp/zdtun`; a configuração de rebuild está em `app/src/main/cpp/CMakeLists.txt`. A única alteração local na API é fazer `on_socket_open` retornar int e abortar a criação do socket quando o callback retorna erro, antes de conectar. Isso impede tráfego sem proteção/bind se o Android recusar uma operação. Não foi copiado código do PCAPdroid.

`third_party/uthash.h` mantém sua licença BSD no próprio arquivo. `third_party/net_headers.h` foi preservado como distribuído no repositório zdtun. As licenças LGPLv3 e GPLv3 estão incluídas em assets/licenses no APK, e os avisos originais foram preservados.

Ao distribuir binários, entregar também a fonte correspondente, alterações, scripts de build e código do aplicativo necessário para reconstruir/revincular a versão combinada, ou um mecanismo equivalente que cumpra a LGPLv3. Não impor proibição à engenharia reversa para depuração das modificações na biblioteca. O projeto completo permite recompilar com biblioteca modificada e instalar um APK de teste assinado pelo destinatário. Esta documentação não atribui automaticamente uma licença ao código próprio do aplicativo. Confirmar as obrigações de distribuição no contexto de qualquer distribuição externa futura.

## Verificação

`tests/native/forwarding_test.c` verificou TCP/UDP e recusa de sockets em loopback no S23 Android 16 API 36 em 2026-10-12, com três resultados PASS. Consulte tests/native/README.md para reproduzir.

`CaptureFormatTest` verifica decodificação UDP, rejeição de fragmentos/truncamento e bytes/cabeçalho/limite do PCAP. O teste funcional no aparelho deve obter consentimento VPN, iniciar captura, executar explicitamente o teste HTTPS, observar bytes nas duas direções, parar e abrir o PCAP. Também verificar revogação, perda de Wi-Fi e ausência de migração do motor para celular. Compilar não comprova compatibilidade física Samsung.

NDK 27 com flags de alinhamento ELF de 16 KiB é configurado para arm64-v8a e x86_64. A compatibilidade final com sistemas de páginas de 16 KiB exige verificar também o alinhamento ZIP/APK e testar no ambiente correspondente.
