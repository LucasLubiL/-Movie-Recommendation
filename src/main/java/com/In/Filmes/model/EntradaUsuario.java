package com.In.Filmes.model;

import java.util.List;

public class EntradaUsuario {
    private double notaMinima;
    private int duracaoMaxima;
    private String classificacao;
    private List<String> generos;

    public double getNotaMinima() {
        return notaMinima;
    }

    public void setNotaMinima(double notaMinima) {
        this.notaMinima = notaMinima;
    }

    public int getDuracaoMaxima() {
        return duracaoMaxima;
    }

    public void setDuracaoMaxima(int duracaoMaxima) {
        this.duracaoMaxima = duracaoMaxima;
    }

    public String getClassificacao() {
        return classificacao;
    }

    public void setClassificacao(String classificacao) {
        this.classificacao = classificacao;
    }

    public List<String> getGeneros() {
        return generos;
    }

    public void setGeneros(List<String> generos) {
        this.generos = generos;
    }
}
