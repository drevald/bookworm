package com.homelibrary.server.service;

import com.homelibrary.server.service.BookParser.ParsedBookData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookParserTest {

    private final BookParser parser = new BookParser();

    @Test
    void parseInfoPage_RussianBook_ExtractsCorrectMetadata() {
        // This is the actual OCR output from page.jpg
        String ocrText = """
к =k
i
УДК 82/89
BEK 84.34
1161
Перевод ? narumeroro Николая Горелова ‚
Перевод с древиегреческого Ольги Горшановой,
: Дарьи Захаровой, Елены Лагутиной
Перевод_со старофранцузского Игоря Минаева
Оформление серии Вадима Пожидаева
Ny
1161 Послания из вымышленного царства / Пер.
© др.-греч., ст.-фр.; Пер. ? лат., COCT., вступ. CT.
H. Горелова. — СПб.: Азбука-классика, 2004. —
224 c.
ISBN 5-352-01102-X
«Послания w3 вымышленного царства» — книга, со-
бравшая под свосй обложкой все предания о Великой ;
Индии — вымышленном царстве пресви тера Иоаниа. Вла-
дения христианского царя, расположенные, как полагали
в Средневековье, где-то между Великой erensio и Страною
шелка, были наполнены веевозможными чудесами, немыс-
лимыми чудовищами и фаитастическими зверями. Богат -
ство преспвитера Иоанна веками волновало и завораживало ?
путешественников, 110 поскольку царство обнаружить не
Улалось, говорили, что опо было захвачено татарами. Bor
откуда появились у них силы совершить Великий поход :
на Запад с целью отобрать у европейцев мощи Волхиов 4
легендарных оспователей державы преевитера Исаниа 3
© H. Горелов, перенод, стать\\ ь
комментарии, 2004 ч
© ©. Гориашова, перевод, 2004 ы ‚° el
© В, Пожидаев, оформление серни, 2004 e -
ISBN 5-352-01102-Х © «Азбука-классика», 2004 ‚
i e
й ‹"‹:…'
' e
""";

        ParsedBookData result = parser.parse("", "", ocrText);

        System.out.println("=== Parsing Results ===");
        System.out.println("Title: " + result.getTitle());
        System.out.println("Author: " + result.getAuthor());
        System.out.println("ISBN: " + result.getIsbn());
        System.out.println("Year: " + result.getPublicationYear());
        System.out.println("Publisher: " + result.getPublisher());
        System.out.println("City: " + result.getCity());
        System.out.println("======================");

        // Verify title contains key words from "Послания из вымышленного царства"
        assertNotNull(result.getTitle(), "Title should not be null");
        String normalizedTitle = result.getTitle().toLowerCase();
        assertTrue(
            normalizedTitle.contains("послания") ||
            normalizedTitle.contains("вымышленного") ||
            normalizedTitle.contains("царства") ||
            normalizedTitle.contains("царство"),
            "Title should contain key words. Got: " + result.getTitle()
        );

        // Verify author
        assertNotNull(result.getAuthor(), "Author should not be null");
        assertTrue(
            result.getAuthor().toLowerCase().contains("горелов"),
            "Author should contain 'Горелов'. Got: " + result.getAuthor()
        );

        // Verify ISBN
        assertNotNull(result.getIsbn(), "ISBN should not be null");
        assertTrue(
            result.getIsbn().replaceAll("-", "").contains("535201102"),
            "ISBN should contain 535201102. Got: " + result.getIsbn()
        );

        // Verify year
        assertNotNull(result.getPublicationYear(), "Year should not be null");
        assertEquals(2004, result.getPublicationYear(), "Year should be 2004");
    }

    @Test
    void parseInfoPage_BolotovMemories_ExtractsCorrectMetadata() {
        // This is the actual OCR output from bolotov_info.jpg
        String ocrText = """
УДК 821.161.1
ББК 84(2Рос=Рус)
Б79

Оформление художника
В. ДАНЧЕНКО

Болотов А. Т.
Б79   Жизнь и приключения Андрея Болотова, описанные
самим им для своих потомков: В 3 т. Т. 1: 1738-1759 гг. —
М.: Книжный Клуб Книговек, 2018. — 464 с. — (Литера-
турные памятники русского быта).

ISBN 978-5-4224-1415-4 (т. 1)
ISBN 978-5-4224-1414-7

Андрей Тимофеевич Болотов (1738-1833) получил известность бла-
годаря своему обширному труду «Жизнь и приключения Андрея Болото-
ва», написанные самим им для своих потомков. Короткую биографию
внутренней быт общества XVIII века, он подробно описывает процесс
воспитания русской дворянина, до домашнего, общественную и военную
жизнь. Помимо этого труда Болотова является несомненным достоянием
русской и мировой философской культуры: здесь много важнейших географических
фактов о государственных и общественных делятелях того времени.
Для удобства читателя «Жизнь и приключения Андрея Болотова»
дана в сокращении, при этом сохранена наиболее характерные отрывки
историко- и культурно-бытовых отношений.

УДК 821.161.1
ББК 84(2Рос=Рус)

ISBN 978-5-4224-1415-4 (т. 1)
ISBN 978-5-4224-1414-7

© Книжный Клуб Книговек, 2018
""";

        ParsedBookData result = parser.parse("", "", ocrText);

        System.out.println("=== Parsing Results ===");
        System.out.println("Title: " + result.getTitle());
        System.out.println("Author: " + result.getAuthor());
        System.out.println("ISBN: " + result.getIsbn());
        System.out.println("Year: " + result.getPublicationYear());
        System.out.println("Publisher: " + result.getPublisher());
        System.out.println("City: " + result.getCity());
        System.out.println("======================");

        // Verify title contains key words from "Жизнь и приключения Андрея Болотова"
        assertNotNull(result.getTitle(), "Title should not be null");
        String normalizedTitle = result.getTitle().toLowerCase();
        assertTrue(
            normalizedTitle.contains("жизнь") ||
            normalizedTitle.contains("приключения") ||
            normalizedTitle.contains("болотов"),
            "Title should contain key words. Got: " + result.getTitle()
        );

        // Verify author - Болотов А. Т.
        assertNotNull(result.getAuthor(), "Author should not be null");
        assertTrue(
            result.getAuthor().toLowerCase().contains("болотов"),
            "Author should contain 'Болотов'. Got: " + result.getAuthor()
        );

        // Verify ISBN
        assertNotNull(result.getIsbn(), "ISBN should not be null");
        assertTrue(
            result.getIsbn().replaceAll("-", "").contains("9785422414154") ||
            result.getIsbn().replaceAll("-", "").contains("9785422414147"),
            "ISBN should contain one of the book ISBNs. Got: " + result.getIsbn()
        );

        // Verify year
        assertNotNull(result.getPublicationYear(), "Year should not be null");
        assertEquals(2018, result.getPublicationYear(), "Year should be 2018");
    }
}
