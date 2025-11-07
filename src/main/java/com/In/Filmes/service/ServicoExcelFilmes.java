package com.In.Filmes.service;

import com.In.Filmes.model.Filme;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ServicoExcelFilmes — versão segura e "lazy" para resolução de posters.
 *
 * Principais mudanças:
 * - NÃO faz fetch em massa durante carregarFilmes().
 * - Fornece fetchPosterForFilme(Filme) para buscar poster apenas sob demanda.
 * - Cache local (em memória) com TTL/controle de falhas para evitar loops e rate-limit.
 * - Usa java.net.http.HttpClient e observa Retry-After quando presente.
 */
@Service
public class ServicoExcelFilmes {

    private static final Logger logger = LoggerFactory.getLogger(ServicoExcelFilmes.class);

    // cache simples para os filmes lidos do Excel (evitar reler)
    private List<Filme> cacheFilmes;

    // cache para resolução de posters: mapeia posterRaw -> CachedPoster
    private final Map<String, CachedPoster> posterCache = new ConcurrentHashMap<>();

    // Http client compartilhado
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    // Se true, tentará buscar posters durante carga (NÃO RECOMENDADO para grandes planilhas)
    // Mantenha false em produção; pode ser sobrescrito via application.properties se desejar.
    @Value("${filmes.fetchPostersDuringLoad:false}")
    private boolean fetchPostersDuringLoad;

    // Chave TMDB (opcional). Configure em application.properties: tmdb.api.key=xxxx
    @Value("${tmdb.api.key:}")
    private String tmdbApiKey;

    // quando uma tentativa falha, aguardamos esse tempo (segundos) antes de re-tentar (default)
    private static final long FAIL_RETRY_SECONDS_DEFAULT = 60 * 60; // 1 hora

    // TTL de sucesso (segundos) — depois desse tempo tentaremos atualizar (opcional)
    private static final long SUCCESS_TTL_SECONDS = 24 * 60 * 60; // 24 horas

    // placeholder local a usar quando nada resolvido (apontar recurso estático)
    private static final String PLACEHOLDER_POSTER = "/images/placeholder_poster.png";

    // ---- estruturas internas ----
    private static class CachedPoster {
        final String posterUrl;       // url final de imagem (ex: image.tmdb.org/t/p/w500/xxx.jpg) ou placeholder
        final Instant fetchedAt;
        final Instant failUntil;      // se fetch falhou, tempo até qual não tentaremos novamente

        CachedPoster(String posterUrl, Instant fetchedAt, Instant failUntil) {
            this.posterUrl = posterUrl;
            this.fetchedAt = fetchedAt;
            this.failUntil = failUntil;
        }

        boolean isStale() {
            return fetchedAt == null || fetchedAt.plusSeconds(SUCCESS_TTL_SECONDS).isBefore(Instant.now());
        }

        boolean isFailed() {
            return failUntil != null && failUntil.isAfter(Instant.now());
        }
    }

    // ----- API pública -----


    /**
     * Carrega filmes do Excel (apenas leitura). NÃO faz fetch de posters em massa por padrão.
     * @param caminhoExcel caminho para o arquivo .xlsx
     * @return lista de filmes
     */
    public List<Filme> carregarFilmes(String caminhoExcel) throws Exception {
        if (cacheFilmes != null) return cacheFilmes;

        Path p = Path.of(caminhoExcel);
        if (!Files.exists(p)) throw new IllegalArgumentException("Arquivo Excel não encontrado: " + caminhoExcel);

        try (InputStream is = new FileInputStream(p.toFile()); Workbook wb = new XSSFWorkbook(is)) {
            Sheet planilha = wb.getSheetAt(0);
            Iterator<Row> linhas = planilha.iterator();
            if (!linhas.hasNext()) return Collections.emptyList();

            Row cabecalho = linhas.next();
            List<String> nomesColunas = new ArrayList<>();
            for (Cell c : cabecalho) {
                String header = c == null ? "" : c.getStringCellValue();
                nomesColunas.add(header == null ? "" : header.trim());
            }

            List<Filme> filmes = new ArrayList<>();
            while (linhas.hasNext()) {
                Row r = linhas.next();
                Filme f = new Filme();
                Map<String, String> brutos = new LinkedHashMap<>();
                Map<String, Double> normalizados = new HashMap<>();

                for (int i = 0; i < nomesColunas.size(); i++) {
                    Cell c = r.getCell(i);
                    String valor = "";
                    if (c != null) {
                        switch (c.getCellType()) {
                            case STRING:
                                valor = c.getStringCellValue();
                                break;
                            case NUMERIC:
                                valor = String.valueOf(c.getNumericCellValue());
                                break;
                            case BOOLEAN:
                                valor = String.valueOf(c.getBooleanCellValue());
                                break;
                            default:
                                valor = c.toString();
                        }
                    }
                    String col = nomesColunas.get(i);
                    brutos.put(col == null ? ("col" + i) : col, valor == null ? "" : valor);

                    String colLower = (col == null ? "" : col.toLowerCase());
                    if (colLower.contains("norm") || colLower.contains("normalized") || colLower.contains("normaliz")
                            || colLower.contains("normalizer") || colLower.contains("_norm") || colLower.contains("normalized")) {
                        try {
                            normalizados.put(col, Double.parseDouble(valor.replace(',', '.')));
                        } catch (Exception ignored) {
                        }
                    }
                }

                // pegar campos principais com busca robusta
                String titulo = buscarValor(brutos, "title", "titulo", "name", "nome");
                String cluster = buscarValor(brutos, "cluster", "clusterid", "cluster_id", "clusterid");
                Double nota = tentarDouble(buscarValor(brutos, "score", "nota", "rating"));
                Integer dur = tentarInt(buscarValor(brutos, "duration", "duracao", "runtime", "length"));
                String classificacao = buscarValor(brutos, "classification", "classificacao", "rating_age", "age");

                f.setTitulo(titulo);
                f.setCluster(cluster);
                f.setNota(nota);
                f.setDuracao(dur);
                f.setClassificacao(classificacao);
                f.setValoresBrutos(brutos);
                f.setValoresNormalizados(normalizados);

                // generos
                String generosStr = buscarValor(brutos, "genres", "generos", "genre", "genero");
                if (generosStr != null && !generosStr.isBlank()) {
                    List<String> g = Arrays.stream(generosStr.split("[,;|\\/]"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                    f.setGeneros(g);
                } else {
                    // detectar colunas binárias (acao,comedia,...)
                    List<String> detected = new ArrayList<>();
                    Map<String, String> normalizedBrutos = new HashMap<>();
                    for (Map.Entry<String, String> e : brutos.entrySet()) {
                        normalizedBrutos.put(normalizeKey(e.getKey()), e.getValue());
                    }
                    Map<String, String> genreCandidates = Map.of(
                            "acao", "Ação",
                            "action", "Ação",
                            "comedia", "Comédia",
                            "comedy", "Comédia",
                            "terror", "Terror",
                            "horror", "Terror",
                            "animacao", "Animação",
                            "animation", "Animação",
                            "crime", "Crime"
                    );
                    for (Map.Entry<String, String> cand : genreCandidates.entrySet()) {
                        if (normalizedBrutos.containsKey(cand.getKey())) {
                            String v = normalizedBrutos.get(cand.getKey());
                            if (v != null) {
                                String vv = v.trim();
                                try {
                                    double dv = Double.parseDouble(vv.replace(',', '.'));
                                    if (dv != 0.0) detected.add(cand.getValue());
                                } catch (Exception ex) {
                                    if (vv.equalsIgnoreCase("1") || vv.equalsIgnoreCase("true")
                                            || vv.equalsIgnoreCase("sim") || vv.equalsIgnoreCase("yes")) {
                                        detected.add(cand.getValue());
                                    }
                                }
                            }
                        }
                    }
                    if (!detected.isEmpty()) f.setGeneros(detected);
                }

                // Popular campos diretos para a view (evita lógica complexa em Thymeleaf)
                // NOTE: aqui não fazemos fetch. Apenas copiamos o valor bruto.
                String poster = buscarValor(brutos, "poster", "poster_path", "imagem", "image", "posterUrl", "poster_url", "image_path", "tmdb_url", "tmdb");
                f.setCaminhoPoster(poster == null ? "" : poster);

                String diretor = buscarValor(brutos, "diretor", "director", "direcao", "direction");
                f.setDiretor(diretor == null ? "" : diretor);

                String estrela = buscarValor(brutos, "estrela", "star", "ator", "actor", "lead");
                f.setEstrela(estrela == null ? "" : estrela);

                String descricao = buscarValor(brutos, "descricao", "description", "sinopse", "overview", "resumo");
                f.setDescricao(descricao == null ? "" : descricao);

                filmes.add(f);
            }

            this.cacheFilmes = filmes;
            logger.info("Carregados {} filmes do Excel: {}", filmes.size(), arquivosParaLog(filmes));

            // Se configurado explicitamente para FORÇAR fetch durante load (não recomendado),
            // faz fetch somente se fetchPostersDuringLoad == true e apiKey presente.
            if (fetchPostersDuringLoad && tmdbApiKey != null && !tmdbApiKey.isBlank()) {
                logger.warn("fetchPostersDuringLoad=true -> resolvendo posters durante carga (pode levar muito tempo)...");
                for (Filme f : filmes) {
                    safeResolvePosterForLoad(f);
                }
            }

            return filmes;
        }
    }

    /**
     * Método seguro para uso durante telas: resolve (fetch) o poster apenas para um filme específico.
     * - usa cache para não repetir fetchs
     * - respeita cooldown em falhas
     * - retorna URL final (ou placeholder)
     */
    public String fetchPosterForFilme(Filme filme) {
        if (filme == null) return PLACEHOLDER_POSTER;

        String raw = filme.getCaminhoPoster();
        if (raw == null || raw.isBlank()) {
            // sem informação bruta: tenta compor a partir do title (apenas se apiKey fornecida)
            if (tmdbApiKey == null || tmdbApiKey.isBlank()) {
                logger.debug("fetchPosterForFilme: sem raw e sem apiKey -> placeholder para '{}'", filme.getTitulo());
                return PLACEHOLDER_POSTER;
            } else if (filme.getTitulo() == null || filme.getTitulo().isBlank()) {
                return PLACEHOLDER_POSTER;
            } else {
                raw = buildTmdbSearchUrlFromTitle(filme.getTitulo());
            }
        }

        // primeiro, se raw parece já ser a URL direta de imagem (termina com jpg/png/webp) -> devolve direto
        if (looksLikeDirectImageUrl(raw)) {
            logger.debug("fetchPosterForFilme: raw já é imagem direta para '{}': {}", filme.getTitulo(), raw);
            // cache simples (evita reprocessar)
            posterCache.putIfAbsent(raw, new CachedPoster(raw, Instant.now(), null));
            filme.setCaminhoPoster(raw);
            return raw;
        }

        // se raw é uma chamada de API TMDB (search) ou outro endpoint que retorna JSON,
        // usamos cacheKey = raw
        String cacheKey = raw;

        // checar cache
        CachedPoster cp = posterCache.get(cacheKey);
        if (cp != null) {
            if (cp.isFailed()) {
                logger.debug("fetchPosterForFilme: cache indica falha recente para '{}' -> não tentar agora", cacheKey);
                filme.setCaminhoPoster(cp.posterUrl);
                return cp.posterUrl;
            }
            // se não está falhado e não stale -> retornar
            if (!cp.isStale()) {
                logger.debug("fetchPosterForFilme: retorno de cache para '{}'", cacheKey);
                filme.setCaminhoPoster(cp.posterUrl);
                return cp.posterUrl;
            }
            // se está stale, tentamos atualizar abaixo (com cautela)
        }

        // executar fetch com tratamento de status (sem retries infinitos)
        try {
            String fetchedJson = fetchUrlBodyAsString(raw, 8000);
            if (fetchedJson == null || fetchedJson.isBlank()) {
                // marca falha temporária
                Instant until = Instant.now().plusSeconds(FAIL_RETRY_SECONDS_DEFAULT);
                posterCache.put(cacheKey, new CachedPoster(PLACEHOLDER_POSTER, Instant.now(), until));
                logger.warn("fetchPosterForFilme: fetch retornou vazio. Marcar falha até {}", until);
                filme.setCaminhoPoster(PLACEHOLDER_POSTER);
                return PLACEHOLDER_POSTER;
            }

            // tentar extrair poster_path do JSON (assumimos formato TMDB search results)
            String posterPath = extractPosterPathFromTmdbSearchJson(fetchedJson);
            if (posterPath != null && !posterPath.isBlank()) {
                String finalUrl = "https://image.tmdb.org/t/p/w500" + posterPath;
                posterCache.put(cacheKey, new CachedPoster(finalUrl, Instant.now(), null));
                filme.setCaminhoPoster(finalUrl);
                logger.debug("fetchPosterForFilme: resolved poster para '{}' -> {}", filme.getTitulo(), finalUrl);
                return finalUrl;
            } else {
                // não encontrou poster_path -> marcar placeholder e não tentar de novo por algum tempo
                Instant until = Instant.now().plusSeconds(FAIL_RETRY_SECONDS_DEFAULT);
                posterCache.put(cacheKey, new CachedPoster(PLACEHOLDER_POSTER, Instant.now(), until));
                filme.setCaminhoPoster(PLACEHOLDER_POSTER);
                logger.debug("fetchPosterForFilme: não encontrou poster_path no JSON para '{}'. Marcado falha até {}", filme.getTitulo(), until);
                return PLACEHOLDER_POSTER;
            }
        } catch (FetchHttpException fhe) {
            // se API respondeu com 401/429/Retry-After, respeitamos e marcamos falha temporária
            long wait = fhe.retryAfterSeconds > 0 ? fhe.retryAfterSeconds : FAIL_RETRY_SECONDS_DEFAULT;
            Instant until = Instant.now().plusSeconds(wait);
            posterCache.put(cacheKey, new CachedPoster(PLACEHOLDER_POSTER, Instant.now(), until));
            logger.warn("fetchPosterForFilme: exception ao buscar '{}'. status={}, retryAfter={} -> marcar falha até {}",
                    cacheKey, fhe.status, fhe.retryAfterSeconds, until);
            filme.setCaminhoPoster(PLACEHOLDER_POSTER);
            return PLACEHOLDER_POSTER;
        } catch (Exception e) {
            Instant until = Instant.now().plusSeconds(FAIL_RETRY_SECONDS_DEFAULT);
            posterCache.put(cacheKey, new CachedPoster(PLACEHOLDER_POSTER, Instant.now(), until));
            logger.warn("fetchPosterForFilme: erro inesperado ao buscar '{}': {}. Falha até {}", cacheKey, e.toString(), until);
            filme.setCaminhoPoster(PLACEHOLDER_POSTER);
            return PLACEHOLDER_POSTER;
        }
    }

    // ----- implementação auxiliar / interna -----

    // tenta resolver posters durante carga (usar com cuidado — aqui usamos fetchPosterForFilme por filme)
    private void safeResolvePosterForLoad(Filme f) {
        try {
            fetchPosterForFilme(f);
        } catch (Exception e) {
            logger.debug("safeResolvePosterForLoad erro: {}", e.toString());
        }
    }

    // detecta urls que são imagens diretas
    private static boolean looksLikeDirectImageUrl(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase().trim();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.contains("image.tmdb.org/t/p");
    }

    // extrai poster_path simples do JSON retornado pela TMDB search (sem bibliotecas JSON aqui — parse rudimentar)
    // tenta encontrar "poster_path":"...".
    private static String extractPosterPathFromTmdbSearchJson(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"poster_path\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int firstQuote = json.indexOf('"', colon);
        if (firstQuote < 0) return null;
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return null;
        String path = json.substring(firstQuote + 1, secondQuote);
        if (path == null || path.isBlank() || path.equals("null")) return null;
        // path normalmente é algo como "/abc123.jpg"
        return path;
    }

    // fetch do corpo string com tratamento de códigos e Retry-After
    private String fetchUrlBodyAsString(String urlStr, int timeoutMs) throws FetchHttpException {
        try {
            URI uri = new URL(urlStr).toURI();
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(Math.max(2000, timeoutMs)))
                    .GET()
                    .header("User-Agent", "ServicoExcelFilmes/1.0")
                    .header("Accept", "application/json");

            HttpRequest req = rb.build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                return resp.body();
            } else {
                long retryAfter = parseRetryAfterSeconds(resp);
                // lançar ex para o chamador lidar
                throw new FetchHttpException(status, retryAfter, "HTTP " + status);
            }
        } catch (FetchHttpException fhe) {
            throw fhe;
        } catch (Exception e) {
            // wrap em FetchHttpException com status -1
            throw new FetchHttpException(-1, 0, e.getMessage());
        }
    }

    private long parseRetryAfterSeconds(HttpResponse<?> resp) {
        Optional<String> header = resp.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            String v = header.get();
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException nfe) {
                // pode ser data HTTP; em vez de parse complexo, ignoramos
            }
        }
        return 0L;
    }

    private static class FetchHttpException extends Exception {
        final int status;
        final long retryAfterSeconds;
        FetchHttpException(int status, long retryAfterSeconds, String msg) {
            super(msg);
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    // constrói URL de busca TMDB a partir do título (somente se tiver apiKey)
    private String buildTmdbSearchUrlFromTitle(String title) {
        if (tmdbApiKey == null || tmdbApiKey.isBlank()) return "";
        try {
            String q = java.net.URLEncoder.encode(title, "UTF-8");
            return "https://api.themoviedb.org/3/search/movie?api_key=" + tmdbApiKey + "&query=" + q;
        } catch (Exception e) {
            return "";
        }
    }

    // ---- utilidades que já existiam (preservadas) ----

    private String arquivosParaLog(List<Filme> filmes) {
        if (filmes == null || filmes.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(5, filmes.size());
        for (int i = 0; i < limit; i++) {
            Filme f = filmes.get(i);
            sb.append(f.getTitulo() == null ? "(sem titulo)" : f.getTitulo());
            sb.append(" [");
            if (f.getGeneros() != null) sb.append(String.join(",", f.getGeneros()));
            sb.append("]");
            if (i < limit - 1) sb.append(", ");
        }
        if (filmes.size() > limit) sb.append(" ...");
        return sb.toString();
    }

    private static String normalizeKey(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        n = n.trim().toLowerCase();
        n = n.replaceAll("[^0-9a-z]", "");
        return n;
    }

    private static String buscarValor(Map<String, String> mapa, String... chaves) {
        if (mapa == null || mapa.isEmpty()) return null;
        Map<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> e : mapa.entrySet()) {
            normalized.put(normalizeKey(e.getKey()), e.getValue());
        }
        for (String k : chaves) {
            String nk = normalizeKey(k);
            if (normalized.containsKey(nk)) return normalized.get(nk);
        }
        for (String k : chaves) {
            String nk = normalizeKey(k);
            for (String keyNorm : normalized.keySet()) {
                if (keyNorm.contains(nk) || nk.contains(keyNorm)) return normalized.get(keyNorm);
            }
        }
        return null;
    }

    private static Double tentarDouble(String s) {
        if (s == null) return null;
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer tentarInt(String s) {
        if (s == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(s.replace(',', '.')));
        } catch (Exception e) {
            return null;
        }
    }
}
