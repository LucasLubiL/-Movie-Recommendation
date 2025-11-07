package com.In.Filmes.controller;

import com.In.Filmes.model.EntradaUsuario;
import com.In.Filmes.model.Filme;
import com.In.Filmes.service.ServicoRecomendacao;
import com.In.Filmes.service.ServicoExcelFilmes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

@Controller
public class ControladorPrincipal {

    private static final Logger logger = LoggerFactory.getLogger(ControladorPrincipal.class);

    private final ServicoRecomendacao servicoRecomendacao;
    private final ServicoExcelFilmes servicoExcelFilmes;

    public ControladorPrincipal(ServicoRecomendacao servicoRecomendacao,
                                ServicoExcelFilmes servicoExcelFilmes) {
        this.servicoRecomendacao = servicoRecomendacao;
        this.servicoExcelFilmes = servicoExcelFilmes;
    }

    @GetMapping({"/", "/home"})
    public String telaInicial(Model model) {
        model.addAttribute("entradaUsuario", new EntradaUsuario());
        model.addAttribute("recomendacoes", Collections.emptyList());
        model.addAttribute("erro", null);
        return "html/index";
    }

    @PostMapping("/recomendar")
    public String recomendar(@RequestParam(required = false) Double score,
                             @RequestParam(required = false) Integer duration,
                             @RequestParam(required = false) String classification,
                             // vamos receber apenas 1 gênero (radio) — ajustar name no HTML para "genre"
                             @RequestParam(required = false, name = "genre") String genre,
                             Model model) {

        EntradaUsuario entrada = new EntradaUsuario();
        entrada.setNotaMinima(score == null ? 0.0 : score);
        entrada.setDuracaoMaxima(duration == null ? 0 : duration);
        entrada.setClassificacao(classification);

        // Converte o gênero (se informado) para lista de 1 elemento — código de serviço espera List<String>
        if (genre != null && !genre.isBlank()) {
            List<String> gl = new ArrayList<>();
            gl.add(genre);
            entrada.setGeneros(gl);
        } else {
            entrada.setGeneros(null);
        }

        // validação no servidor (restringe envio inválido)
        if (classification == null || classification.isBlank()) {
            model.addAttribute("recomendacoes", Collections.emptyList());
            model.addAttribute("entradaUsuario", entrada);
            model.addAttribute("erro", "Por favor selecione uma classificação etária.");
            return "html/index";
        }
        if (genre == null || genre.isBlank()) {
            model.addAttribute("recomendacoes", Collections.emptyList());
            model.addAttribute("entradaUsuario", entrada);
            model.addAttribute("erro", "Por favor selecione um gênero.");
            return "html/index";
        }

        try {
            List<Filme> recomendados = servicoRecomendacao.recomendar(entrada);
            if (recomendados == null) recomendados = Collections.emptyList();

            // ----- AQUI: resolver posters apenas para os N primeiros (3) -----
            int toResolve = Math.min(recomendados.size(), 3);
            for (int i = 0; i < toResolve; i++) {
                Filme f = recomendados.get(i);
                try {
                    // fetchPosterForFilme atualiza f.setCaminhoPoster(...) internamente e retorna a url
                    servicoExcelFilmes.fetchPosterForFilme(f);
                } catch (Exception ex) {
                    // não deixar que falha de poster quebre a recomendação — logamos e seguimos
                    logger.warn("Falha ao resolver poster para filme '{}': {}", 
                                f == null ? "<null>" : f.getTitulo(), ex.toString());
                }
            }
            // ---------------------------------------------------------------

            model.addAttribute("recomendacoes", recomendados);
            model.addAttribute("erro", null);
        } catch (Exception e) {
            model.addAttribute("recomendacoes", Collections.emptyList());
            model.addAttribute("erro", "Erro ao processar recomendação: " + e.getMessage());
        }

        model.addAttribute("entradaUsuario", entrada);
        return "html/index";
    }

}
