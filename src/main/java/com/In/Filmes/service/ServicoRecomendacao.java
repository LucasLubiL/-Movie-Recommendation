package com.In.Filmes.service;

import com.In.Filmes.model.Filme;
import com.In.Filmes.model.EntradaUsuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Locale;
import java.util.OptionalLong;

@Service
public class ServicoRecomendacao {

    private static final Logger logger = LoggerFactory.getLogger(ServicoRecomendacao.class);
    private static final Pattern DIGITS = Pattern.compile("(\\d+)(?!.*\\d)");

    private final ServicoExcelFilmes servicoExcel;
    private final ServicoPmmlClusters servicoPmml;

    // caminhos (ajuste se quiser)
    private String caminhoExcel = "C:/Users/Usuario/Desktop/Trabalhos Faculdade/5 periodo/Inteligência de Negócios/parte 3/FilmesExcel.xlsx";
    private String caminhoPmml  = "C:/Users/Usuario/Desktop/Trabalhos Faculdade/5 periodo/Inteligência de Negócios/parte 3/FilmesPmml.pmml";

    private List<Filme> cache;

    // tokens de gêneros esperados (normalizados)
    private static final List<String> GENRE_TOKENS = Arrays.asList("acao","action","crime","terror","horror","comedia","comedy","animacao","animation","anime");

    public ServicoRecomendacao(ServicoExcelFilmes servicoExcel, ServicoPmmlClusters servicoPmml) {
        this.servicoExcel = servicoExcel;
        this.servicoPmml = servicoPmml;
    }

    /**
     * Versão "soft scoring" — penaliza divergências, não exclui.
     */
    public List<Filme> recomendar(EntradaUsuario entrada) throws Exception {
        // 1) carregar cache
        if (cache == null) {
            cache = servicoExcel.carregarFilmes(caminhoExcel);
            logger.info("[RECOM] Cache inicializado com {} filmes", cache == null ? 0 : cache.size());
        } else {
            logger.info("[RECOM] Usando cache com {} filmes", cache.size());
        }

        // 2) carregar PMML e obter ordem de campos (se existir)
        servicoPmml.carregarPmml(caminhoPmml);
        List<String> camposPmml = servicoPmml.getNomesCampos();
        logger.info("[RECOM] Campos PMML (ordem): {}", camposPmml);

        if (entrada == null) entrada = new EntradaUsuario();
        logger.info("[RECOM] EntradaUsuario: notaMinima={}, duracaoMaxima={}, classificacao={}, generos={}",
                entrada.getNotaMinima(), entrada.getDuracaoMaxima(), entrada.getClassificacao(), entrada.getGeneros());

        // construir vetor usuario (compatível com PMML se houver)
        double[] vetorUsuario = (camposPmml != null && !camposPmml.isEmpty())
                ? construirVetorUsuario(camposPmml, entrada)
                : new double[]{entrada.getNotaMinima(), entrada.getDuracaoMaxima()};
        logger.info("[RECOM] VetorUsuario (len={}): {}", vetorUsuario.length, vetorToString(vetorUsuario));

        // prever cluster (usaremos como bônus, não filtro)
        String clusterUsuario = servicoPmml.preverCluster(vetorUsuario);
        logger.info("[RECOM] Cluster previsto para o usuário: '{}'", clusterUsuario);

        // preparar detecções globais
        boolean hasNormScore = cache.stream().anyMatch(f -> f.getValoresNormalizados() != null && containsKeyIgnoreCaseD(f.getValoresNormalizados(), "score"));
        boolean hasNormDur = cache.stream().anyMatch(f -> f.getValoresNormalizados() != null && containsKeyIgnoreCaseD(f.getValoresNormalizados(), "dur"));
        boolean hasNormClass = cache.stream().anyMatch(f -> f.getValoresNormalizados() != null && containsKeyIgnoreCaseD(f.getValoresNormalizados(), "class"));
        logger.info("[RECOM] hasNormScore={}, hasNormDur={}, hasNormClass={}", hasNormScore, hasNormDur, hasNormClass);

        Map<String, String> globalBinaryKeys = buildGlobalBinaryKeys(cache);

        // min/max para raw score/dur/class (fallback)
        double minScore = Double.POSITIVE_INFINITY, maxScore = Double.NEGATIVE_INFINITY;
        int minDur = Integer.MAX_VALUE, maxDur = Integer.MIN_VALUE;
        int minFilmClassRaw = Integer.MAX_VALUE, maxFilmClassRaw = Integer.MIN_VALUE;
        boolean haveAnyFilmClassRaw = false;
        for (Filme f : cache) {
            if (f.getNota() != null) { minScore = Math.min(minScore, f.getNota()); maxScore = Math.max(maxScore, f.getNota()); }
            if (f.getDuracao() != null) { minDur = Math.min(minDur, f.getDuracao()); maxDur = Math.max(maxDur, f.getDuracao()); }
            Integer p = parseClassificacaoParaIdade(f.getClassificacao());
            if (p != null) { haveAnyFilmClassRaw = true; minFilmClassRaw = Math.min(minFilmClassRaw, p); maxFilmClassRaw = Math.max(maxFilmClassRaw, p); }
        }
        if (!Double.isFinite(minScore)) { minScore = 0; maxScore = 10; }
        if (minDur == Integer.MAX_VALUE) { minDur = 0; }
        if (maxDur == Integer.MIN_VALUE) { maxDur = 240; }
        if (!haveAnyFilmClassRaw) { minFilmClassRaw = 0; maxFilmClassRaw = 18; }

        // interpretar classificação do usuário (mas sem excluir)
        Integer userClassAge = null;
        String userClassRaw = entrada.getClassificacao();
        if (userClassRaw != null && !userClassRaw.isBlank()) {
            Integer parsed = parseClassificacaoParaIdade(userClassRaw);
            if (parsed != null) userClassAge = parsed;
            else if (userClassRaw.trim().equalsIgnoreCase("l") || userClassRaw.trim().equalsIgnoreCase("livre")) userClassAge = 0;
        }

        // normalizar entrada para distância
        double userScoreNorm = normalizeValueForScore(entrada.getNotaMinima(), hasNormScore, minScore, maxScore);
        double userDurNorm = normalizeValueForDuration(entrada.getDuracaoMaxima(), hasNormDur, minDur, maxDur);
        double userClassNorm = (userClassAge == null) ? 0.0 : (haveAnyFilmClassRaw && maxFilmClassRaw - minFilmClassRaw != 0
                ? clamp01((userClassAge - minFilmClassRaw) / (double)(maxFilmClassRaw - minFilmClassRaw))
                : clamp01(userClassAge / 18.0));

        // PESOS configuráveis: ajuste conforme testes
        final double wScore = 0.5;      // importância da nota
        final double wDur = 0.2;        // importância da duração
        final double wClass = 0.15;     // importância da classificação
        final double wGenre = 0.1;      // importância do gênero (penalidade se não bate)
        final double wClusterBonus = 0.05; // bônus (reduz distância) se cluster bate
        // nota: wScore + wDur + wClass + wGenre = 0.95; cluster é bônus adicional

        List<ScoredFilme> scored = new ArrayList<>();

        for (Filme f : cache) {
            // get normalized features for film
            double filmScoreNorm = getNormalizedScoreForFilm(f, hasNormScore, minScore, maxScore);
            double filmDurNorm = getNormalizedDurationForFilm(f, hasNormDur, minDur, maxDur);

            double filmClassNorm = 0.0;
            if (hasNormClass) {
                Map<String, Double> vn = f.getValoresNormalizados();
                if (vn != null) {
                    for (Map.Entry<String, Double> e : vn.entrySet()) {
                        String k = e.getKey();
                        if (k != null && k.toLowerCase().contains("class")) {
                            Double v = e.getValue();
                            if (v != null && Double.isFinite(v)) { filmClassNorm = clamp01(v); break; }
                        }
                    }
                }
            } else {
                Integer filmAgeParsed = parseClassificacaoParaIdade(f.getClassificacao());
                if (filmAgeParsed != null) {
                    filmClassNorm = (haveAnyFilmClassRaw && maxFilmClassRaw - minFilmClassRaw != 0)
                            ? clamp01((filmAgeParsed - minFilmClassRaw) / (double)(maxFilmClassRaw - minFilmClassRaw))
                            : clamp01(filmAgeParsed / 18.0);
                } else {
                    filmClassNorm = 0.0;
                }
            }

            // distância contínua nas dimensões principais (score, dur, class)
            double ds = Math.pow(userScoreNorm - filmScoreNorm, 2);
            double dd = Math.pow(userDurNorm - filmDurNorm, 2);
            double dc = Math.pow(userClassNorm - filmClassNorm, 2);

            double baseDistance = Math.sqrt(wScore * ds + wDur * dd + wClass * dc);

            // gênero: compute penalty [0..1] where 0 = perfect match, 1 = no match
            double genrePenalty = computeGenrePenalty(entrada, f, globalBinaryKeys); // 0..1

            // cluster bonus: se clusterUsuario != null e equals, reduz distância em small factor
            double clusterBonusFactor = 1.0;
            if (clusterUsuario != null && f.getCluster() != null && clusterEquals(clusterUsuario, f.getCluster())) {
                clusterBonusFactor = 1.0 - wClusterBonus; // reduz distância
            }

            // combinar distância final: penalidade adicionada (multiplicativa + aditiva)
            double distance = baseDistance * clusterBonusFactor + wGenre * genrePenalty;

            // adicione leve preferência por filmes com nota >= entrada.notaMinima (reduz distância se acima)
            if (f.getNota() != null && entrada.getNotaMinima() > 0 && f.getNota() >= entrada.getNotaMinima()) {
                distance *= 0.95; // pequeno desconto se atende/excede nota desejada
            }

            // também penalize muito filmes com duração muito acima do pedido (mas não exclua): penalidade proporcional
            if (entrada.getDuracaoMaxima() > 0 && f.getDuracao() != null) {
                int diff = f.getDuracao() - entrada.getDuracaoMaxima();
                if (diff > 0) {
                    // penalidade suave: 0.01 por minuto acima, limitada
                    double pen = Math.min(0.5, diff * 0.01);
                    distance += pen;
                }
            }

            scored.add(new ScoredFilme(f, distance));
        }

        // ordenar por distância asc e desempatar por nota desc
        scored.sort((a,b) -> {
            int cmp = Double.compare(a.distance, b.distance);
            if (cmp != 0) return cmp;
            Double na = a.f.getNota() == null ? 0.0 : a.f.getNota();
            Double nb = b.f.getNota() == null ? 0.0 : b.f.getNota();
            return Double.compare(nb, na);
        });

        logger.info("[RECOM] Amostra finais (até 10): {}",
                scored.stream().limit(10)
                        .map(sf -> String.format("%s (nota=%s,dur=%s,cluster=%s,dist=%.5f,gen=%s)",
                                safe(sf.f.getTitulo()), safeDouble(sf.f.getNota()), safeInt(sf.f.getDuracao()), safe(sf.f.getCluster()), sf.distance,
                                sf.f.getGeneros() == null ? "[]" : String.join("/", sf.f.getGeneros())))
                        .collect(Collectors.joining(" | ")));

        List<Filme> result = scored.stream().limit(3).map(sf -> sf.f).collect(Collectors.toList());
        logger.info("[RECOM] Recomendacoes retornadas: {}", result.size());
        return result;
    }

    // ----- helpers -----

    private static class ScoredFilme {
        Filme f;
        double distance;
        ScoredFilme(Filme f, double distance) { this.f = f; this.distance = distance; }
        double getDistance() { return distance; }
    }

    private double computeGenrePenalty(EntradaUsuario entrada, Filme f, Map<String, String> globalBinaryKeys) {
        if (entrada == null || entrada.getGeneros() == null || entrada.getGeneros().isEmpty()) return 0.0;
        // se filmes tem generos textuais -> comparar textualmente (mais confiável)
        if (f.getGeneros() != null && !f.getGeneros().isEmpty()) {
            // queremos 0 se ANY gênero pedido estiver presente; senão 1
            for (String req : entrada.getGeneros()) {
                String reqN = normalizeString(req);
                for (String fg : f.getGeneros()) {
                    String fgN = normalizeString(fg);
                    if (genreMatchesNormalized(fgN, reqN)) return 0.0;
                }
            }
            return 1.0; // não encontrou
        }

        // fallback: usar valoresBrutos + globalBinaryKeys
        Map<String, String> br = f.getValoresBrutos();
        if (br == null || br.isEmpty() || globalBinaryKeys == null || globalBinaryKeys.isEmpty()) {
            return 1.0; // desconhecido -> penaliza
        }

        // se qualquer gênero pedido tiver uma key binária presente com valor truthy -> penalty 0
        for (String req : entrada.getGeneros()) {
            String reqN = normalizeString(req);
            Set<String> synonyms = synonymsFor(reqN);
            boolean matched = false;
            for (Map.Entry<String, String> e : globalBinaryKeys.entrySet()) {
                String originalKey = e.getKey();
                String token = e.getValue(); // ex: "acao"
                // token matches any synonym?
                boolean tokenMatches = synonyms.stream().anyMatch(syn -> token.contains(syn) || syn.contains(token));
                if (!tokenMatches) continue;
                String val = br.get(originalKey);
                if (val != null) {
                    String v = val.trim();
                    try {
                        double dv = Double.parseDouble(v.replace(',', '.'));
                        if (dv != 0.0) { matched = true; break; }
                    } catch (Exception ex) {
                        if (v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("sim") || v.equalsIgnoreCase("yes")) {
                            matched = true; break;
                        }
                    }
                }
            }
            if (matched) return 0.0;
        }
        // se chegou aqui, nenhum gênero pedido encontrado -> penaliza
        return 1.0;
    }

    private double getNormalizedScoreForFilm(Filme f, boolean hasNormalizedScore, double minScore, double maxScore) {
        if (f == null) return 0.0;
        Map<String, Double> norm = f.getValoresNormalizados();
        if (hasNormalizedScore && norm != null) {
            for (Map.Entry<String, Double> e : norm.entrySet()) {
                String k = e.getKey();
                if (k != null && k.toLowerCase().contains("score")) {
                    Double v = e.getValue();
                    if (v != null && Double.isFinite(v)) return clamp01(v);
                }
            }
        }
        Double raw = f.getNota();
        if (raw == null) return 0.0;
        if (maxScore - minScore == 0) return 0.0;
        return clamp01((raw - minScore) / (maxScore - minScore));
    }

    private double getNormalizedDurationForFilm(Filme f, boolean hasNormalizedDuration, int minDur, int maxDur) {
        if (f == null) return 0.0;
        Map<String, Double> norm = f.getValoresNormalizados();
        if (hasNormalizedDuration && norm != null) {
            for (Map.Entry<String, Double> e : norm.entrySet()) {
                String k = e.getKey();
                if (k != null && k.toLowerCase().contains("dur")) {
                    Double v = e.getValue();
                    if (v != null && Double.isFinite(v)) return clamp01(v);
                }
            }
        }
        Integer raw = f.getDuracao();
        if (raw == null) return 0.0;
        if (maxDur - minDur == 0) return 0.0;
        return clamp01((raw - minDur) / (double)(maxDur - minDur));
    }

    // *** IMPLEMENTAÇÕES CORRETAS que faltavam anteriormente ***
    private double normalizeValueForScore(double rawScore, boolean hasNormScore, double minScore, double maxScore) {
        // Se usuário não informou, treat as 0
        if (Double.isNaN(rawScore)) return 0.0;
        // Entry can be a raw score (0..10). If normalized columns exist, we still scale raw->0..1 by min/max.
        if (maxScore - minScore == 0) return 0.0;
        return clamp01((rawScore - minScore) / (maxScore - minScore));
    }

    private double normalizeValueForDuration(double rawDur, boolean hasNormDur, int minDur, int maxDur) {
        if (Double.isNaN(rawDur)) return 0.0;
        if (maxDur - minDur == 0) return 0.0;
        // If user didn't specify (0), return 0 normalized (meaning "no preference")
        if (rawDur <= 0) return 0.0;
        return clamp01((rawDur - minDur) / (double)(maxDur - minDur));
    }

    // construir vetor usuario baseado nos campos PMML (atende Classificacao como campo)
    private double[] construirVetorUsuario(List<String> campos, EntradaUsuario entrada) {
        List<Double> v = new ArrayList<>();
        for (String c : campos) {
            String lc = c == null ? "" : c.toLowerCase();
            if (lc.contains("score") || lc.contains("nota")) {
                v.add(entrada.getNotaMinima());
            } else if (lc.contains("duration") || lc.contains("duracao") || lc.contains("runtime")) {
                v.add((double) entrada.getDuracaoMaxima());
            } else if (lc.contains("class") || lc.contains("classificacao")) {
                Integer parsed = parseClassificacaoParaIdade(entrada.getClassificacao());
                if (parsed != null) v.add(parsed.doubleValue());
                else v.add(0.0);
            } else {
                // gêneros binários esperados no PMML (comedia, terror, animacao, crime, acao)
                if (lc.contains("comedia") || lc.contains("comedy")) {
                    v.add(containsGenre(entrada, "Comédia") ? 1.0 : 0.0);
                } else if (lc.contains("terror") || lc.contains("horror")) {
                    v.add(containsGenre(entrada, "Terror") ? 1.0 : 0.0);
                } else if (lc.contains("animacao") || lc.contains("animation") || lc.contains("anime")) {
                    v.add(containsGenre(entrada, "Animação") ? 1.0 : 0.0);
                } else if (lc.contains("crime")) {
                    v.add(containsGenre(entrada, "Crime") ? 1.0 : 0.0);
                } else if (lc.contains("acao") || lc.contains("action")) {
                    v.add(containsGenre(entrada, "Ação") ? 1.0 : 0.0);
                } else {
                    v.add(0.0);
                }
            }
        }
        double[] out = new double[v.size()];
        for (int i = 0; i < v.size(); i++) out[i] = v.get(i);
        return out;
    }

    private boolean containsGenre(EntradaUsuario entrada, String genero) {
        if (entrada == null || entrada.getGeneros() == null) return false;
        String reqNorm = normalizeString(genero);
        for (String g : entrada.getGeneros()) {
            if (genreMatchesNormalized(normalizeString(g), reqNorm)) return true;
        }
        return false;
    }

    private boolean genreMatchesNormalized(String filmGenreNorm, String reqGenreNorm) {
        if (filmGenreNorm == null || reqGenreNorm == null) return false;
        if (filmGenreNorm.equals(reqGenreNorm)) return true;
        if (filmGenreNorm.contains(reqGenreNorm) || reqGenreNorm.contains(filmGenreNorm)) return true;
        Set<String> filmSyn = synonymsFor(filmGenreNorm);
        Set<String> reqSyn = synonymsFor(reqGenreNorm);
        for (String a : filmSyn) {
            for (String b : reqSyn) {
                if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
            }
        }
        return false;
    }

    // constroi mapa global: originalKey -> tokenNormalized (acao, crime, etc)
    private Map<String, String> buildGlobalBinaryKeys(List<Filme> filmes) {
        Map<String, String> normalizedMap = new LinkedHashMap<>();
        if (filmes == null) return normalizedMap;
        for (Filme f : filmes) {
            Map<String, String> br = f.getValoresBrutos();
            if (br == null) continue;
            for (String key : br.keySet()) {
                String kn = normalizeString(key);
                for (String token : GENRE_TOKENS) {
                    if (kn.contains(token)) {
                        // prefere a primeira ocorrência de uma key que contenha o token
                        normalizedMap.putIfAbsent(key, token);
                    }
                }
            }
        }
        return normalizedMap;
    }

    private static Set<String> synonymsFor(String norm) {
        Set<String> out = new HashSet<>();
        if (norm == null || norm.isBlank()) return out;
        out.add(norm);
        if (norm.contains("acao") || norm.contains("action")) { out.add("acao"); out.add("action"); }
        if (norm.contains("comedia") || norm.contains("comedy")) { out.add("comedia"); out.add("comedy"); }
        if (norm.contains("terror") || norm.contains("horror")) { out.add("terror"); out.add("horror"); }
        if (norm.contains("animacao") || norm.contains("animation") || norm.contains("anime")) { out.add("animacao"); out.add("animation"); out.add("anime"); }
        if (norm.contains("crime")) { out.add("crime"); }
        if (norm.contains("drama")) { out.add("drama"); }
        return out;
    }

    private boolean clusterEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        String na = a.trim();
        String nb = b.trim();

        OptionalLong oa = extractTrailingInt(na);
        OptionalLong ob = extractTrailingInt(nb);
        if (oa.isPresent() && ob.isPresent()) return oa.getAsLong() == ob.getAsLong();
        if (oa.isPresent()) {
            long v = oa.getAsLong();
            if (nb.equals(String.valueOf(v)) || nb.equalsIgnoreCase("cluster_" + v)) return true;
        }
        if (ob.isPresent()) {
            long v = ob.getAsLong();
            if (na.equals(String.valueOf(v)) || na.equalsIgnoreCase("cluster_" + v)) return true;
        }
        return na.equalsIgnoreCase(nb);
    }

    private OptionalLong extractTrailingInt(String s) {
        if (s == null) return OptionalLong.empty();
        Matcher m = DIGITS.matcher(s);
        if (m.find()) {
            try { return OptionalLong.of(Long.parseLong(m.group(1))); } catch (Exception ignored) {}
        }
        try {
            double dv = Double.parseDouble(s);
            return OptionalLong.of(Math.round(dv));
        } catch (Exception ignored) {}
        return OptionalLong.empty();
    }

    private Integer parseClassificacaoParaIdade(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase();
        if (t.equals("l") || t.equals("livre") || t.equals("free") || t.equals("g")) return 0;
        Matcher m = Pattern.compile("(\\d{1,2})").matcher(t);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        try { return (int) Math.round(Double.parseDouble(t.replace(',', '.'))); } catch (Exception ignored) {}
        return null;
    }

    private static boolean containsKeyIgnoreCaseD(Map<String, Double> map, String fragment) {
        if (map == null || map.isEmpty()) return false;
        String frag = fragment == null ? "" : fragment.toLowerCase(Locale.ROOT);
        for (String k : map.keySet()) {
            if (k != null && k.toLowerCase(Locale.ROOT).contains(frag)) return true;
        }
        return false;
    }

    private static double clamp01(double v) { if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0; return Math.max(0.0, Math.min(1.0, v)); }

    private static String safe(String s) { return s == null ? "(null)" : s; }
    private static String safeDouble(Double d) { return d == null ? "(null)" : String.format(Locale.ROOT,"%.2f", d); }
    private static String safeInt(Integer i) { return i == null ? "(null)" : String.valueOf(i); }

    private static String normalizeString(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.replaceAll("[^\\p{Alnum}]", "");
        return n.trim().toLowerCase();
    }

    private static String vetorToString(double[] v) {
        if (v == null) return "[]";
        return Arrays.stream(v).mapToObj(d -> String.format(Locale.ROOT,"%.4f", d)).collect(Collectors.joining(", "));
    }
}
