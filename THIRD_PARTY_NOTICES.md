# Componentes de terceiros

## zdtun

- Autor: Emanuele Faranda e contribuidores, copyright preservado em cada arquivo.
- Fonte: https://github.com/emanuele-f/zdtun
- Commit: `80b7941d4e685d8354346820878eaec0e35b6b1e`.
- Licença: LGPL-3.0-or-later, conforme cabeçalhos das fontes.
- Fonte incorporada: `app/src/main/cpp/zdtun`.
- Licenças embarcadas: `assets/licenses/zdtun-LGPL-3.0.txt` e `assets/licenses/GPL-3.0.txt`.
- Alteração local: callback `on_socket_open` retorna int; erro aborta socket antes de connect/send.
- Build: biblioteca dinâmica `libzdtun.so` separada da ponte JNI. O projeto completo inclui fonte e configuração para substituir, reconstruir e revincular a biblioteca.
- Obrigações e limites de distribuição: consulte CAPTURE.md e os textos integrais das licenças. A inclusão deste componente não significa que qualquer modalidade de distribuição já tenha sido avaliada.

## uthash

O arquivo `app/src/main/cpp/zdtun/third_party/uthash.h` contém copyright de Troy D. Hanson e licença BSD, preservados integralmente no código.

## Dependências Android/JVM

Dependências declaradas no Gradle: AndroidX e Jetpack Compose (Apache-2.0), Kotlin e kotlinx.coroutines (Apache-2.0), OkHttp/Okio (Apache-2.0), jsoup (MIT), JUnit (EPL-1.0, apenas testes), org.json (apenas testes, licença conforme artefato). Os artefatos são resolvidos por Google Maven/Maven Central, com avisos mantidos nas respectivas distribuições. Nenhum código PCAPdroid GPL ou HTTP Toolkit AGPL foi copiado para esta implementação.

## RE2/J

RE2/J 1.8, Google e contribuidores, licença BSD-3-Clause. Motor de expressões regulares com execução linear usado na busca do PCAP. Fonte e licença: https://github.com/google/re2j . O texto integral está em assets/licenses/RE2J-LICENSE.txt.

## Base de portas importada

O aplicativo lê nmap-services fornecido localmente pelo usuário. Nenhuma base Nmap é incorporada ou redistribuída neste projeto. Frequências são ordenadas para selecionar 1.000 portas TCP. A base oficial tem licença própria: https://nmap.org/npsl/ .

## Compose Showcase

O guia usa io.github.jocoand:compose-showcaseview:1.4.5, sob licença MIT. Projeto: https://github.com/jocoand/compose-showcase. Licença incluída em assets/licenses/COMPOSE-SHOWCASE-LICENSE.txt.
