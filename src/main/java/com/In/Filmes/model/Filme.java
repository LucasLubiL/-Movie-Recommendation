package com.In.Filmes.model;

import java.util.List;
import java.util.Map;

public class Filme {
    private String id;
    private String titulo;
    private Double nota;
    private Integer duracao;
    private String classificacao;
    private List<String> generos;
    private String cluster;
    private Map<String, String> valoresBrutos;
    private Map<String, Double> valoresNormalizados;
    // Campos derivados/preparados para a view
    private String caminhoPoster;
    private String diretor;
    private String estrela;
    private String descricao;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }

    public Double getNota() { return nota; }
    public void setNota(Double nota) { this.nota = nota; }

    public Integer getDuracao() { return duracao; }
    public void setDuracao(Integer duracao) { this.duracao = duracao; }

    public String getClassificacao() { return classificacao; }
    public void setClassificacao(String classificacao) { this.classificacao = classificacao; }

    public List<String> getGeneros() { return generos; }
    public void setGeneros(List<String> generos) { this.generos = generos; }

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public Map<String, String> getValoresBrutos() { return valoresBrutos; }
    public void setValoresBrutos(Map<String, String> valoresBrutos) { this.valoresBrutos = valoresBrutos; }

    public Map<String, Double> getValoresNormalizados() { return valoresNormalizados; }
    public void setValoresNormalizados(Map<String, Double> valoresNormalizados) { this.valoresNormalizados = valoresNormalizados; }

    public String getCaminhoPoster() { return caminhoPoster; }
    public void setCaminhoPoster(String caminhoPoster) { this.caminhoPoster = caminhoPoster; }

    public String getDiretor() { return diretor; }
    public void setDiretor(String diretor) { this.diretor = diretor; }

    public String getEstrela() { return estrela; }
    public void setEstrela(String estrela) { this.estrela = estrela; }

    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
}
