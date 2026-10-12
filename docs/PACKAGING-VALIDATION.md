# Validação do empacotamento

Em 12/10/2026, o commit inicial `8bbc95a` foi exportado para um diretório isolado e compilado com o wrapper do projeto, JDK 17, SDK 35 e NDK 27.2.12479018. O APK resultante identifica versão 0.6.0, código 11. Isso evita atribuir resultados de builds antigos às alterações concorrentes do diretório original.

| Verificação | Resultado local |
| --- | --- |
| `assembleDebug` e `assembleRelease` | Aprovados; release permanece sem assinatura |
| `testDebugUnitTest` | 57 testes, zero falhas/erros/ignorados |
| `testReleaseUnitTest` | 57 testes, zero falhas/erros/ignorados |
| `lintDebug` e `lintRelease` | Zero erros e nove avisos em cada variante |
| Workflows | actionlint 1.7.12 aprovado; ShellCheck não disponível nesta execução |
| Wrapper JAR | SHA-256 igual ao checksum oficial de Gradle 8.9 |
| Histórico Git | Gitleaks sem credenciais após exceção exata para um marcador sintético de teste |
| Arquivos versionados | Sem sessões JSON/CSV, PCAP, chaves, dados exportados, caches, APKs ou relatórios privados |
| Prints | Dois PNGs revisados visualmente; sem endereços/IDs de sessão visíveis, sem chunks textuais/EXIF |

O Gitleaks sinalizou originalmente o literal `SYNTHETIC_SECRET_NEVER_VALID_123456` de `tests/lab/lab.py`. A exceção em `.gitleaks.toml` corresponde apenas a esse valor falso, não a todo o arquivo nem a uma categoria de segredo.

Os únicos IPs privados literais no código são exemplos, fixtures e o endereço interno fixo da VPN. Relatórios históricos com informações de sessão e demais screenshots foram excluídos antes do primeiro commit e preservados somente no ambiente local. O commit usa email GitHub noreply.

Não foram executados nesta tarefa testes instrumentados em aparelhos, scans, consultas NVD, probes de rede, testes de instalação/atualização com chave release ou publicação de Release. A execução real de CI pode ser consultada em [Actions](https://github.com/sunsecrn/sun-net-tool/actions). Assinatura e primeira Release continuam dependentes da configuração documentada em [RELEASING.md](RELEASING.md).
