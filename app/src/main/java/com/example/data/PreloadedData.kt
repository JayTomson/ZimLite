package com.example.data

object PreloadedData {

    fun getPreloadedArticlesForArchive(archiveId: String): List<ArticleEntity> {
        val archiveTitle = if (archiveId.contains("wikipedia")) "Wikipedia RU" else "Wikiquote RU"
        
        return if (archiveId.contains("wikipedia")) {
            getWikipediaArticles(archiveId, archiveTitle)
        } else {
            getWikiquoteArticles(archiveId, archiveTitle)
        }
    }

    private fun getWikipediaArticles(archiveId: String, archiveTitle: String): List<ArticleEntity> {
        return listOf(
            ArticleEntity(
                id = "${archiveId}_kotlin",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Kotlin.html",
                title = "Котлин (язык программирования)",
                category = "Программирование",
                excerpt = "Котлин (Kotlin) — современный статически типизированный язык программирования от компании JetBrains, официально поддерживаемый Google для Android-разработки.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Котлин (язык программирования)</h1>
                        <p class="subtitle">Материал из Википедии — свободной энциклопедии</p>
                        
                        <div class="info-box">
                            <strong>Kotlin</strong><br/>
                            <span>Класс языка:</span> Мультипарадигменный, статический<br/>
                            <span>Появился в:</span> 2011 г.<br/>
                            <span>Автор:</span> JetBrains<br/>
                            <span>Лицензия:</span> Apache License 2.0
                        </div>

                        <p><strong>Kotlin</strong> — кроссплатформенный статически типизированный язык программирования общего назначения. Разработан компанией JetBrains, штаб-квартира которой находится в Праге. Kotlin полностью совместим с Java и компилируется в байт-код JVM, JavaScript или нативный код.</p>
                        
                        <h2>История создания</h2>
                        <p>JetBrains представила проект Kotlin в июле 2011 года. Руководитель разработки Дмитрий Жемеров заявил, что большинство языков не имеют тех функций, которые им нужны, за исключением Scala, но она компилируется слишком медленно. Главной целью Kotlin было создание современного языка, который бы компилировался так же быстро, как Java.</p>

                        <h2>Преимущества языка</h2>
                        <ul>
                            <li><strong>Безопасность (Null Safety):</strong> Язык встроенно разделяет типы на nullable и non-nullable, что снижает вероятность возникновения ошибок NullPointerException на 90%.</li>
                            <li><strong>Лаконичность:</strong> Объем бойлерплейт-кода сокращается на 30-40% по сравнению с Java.</li>
                            <li><strong>Совместимость с Java:</strong> Вы можете бесшовно вызывать Java-код из Kotlin и наоборот.</li>
                            <li><strong>Поддержка Jetpack Compose:</strong> Kotlin является главным языком для разработки современных декларативных интерфейсов на Android.</li>
                        </ul>

                        <h2>Пример кода на Kotlin</h2>
                        <pre><code>
fun main() {
    val languages = listOf("Kotlin", "Java", "C++", "Python")
    val modern = languages.filter { it == "Kotlin" }
    println("Самый современный язык: ${'$'}{modern.first()}")
}
                        </code></pre>

                        <blockquote>
                            «Kotlin делает разработчиков счастливыми, возвращая радость от написания кода каждый день».
                            <cite>— Команда разработки JetBrains</cite>
                        </blockquote>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_kiwix",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Kiwix.html",
                title = "Kiwix — Офлайн доступные знания",
                category = "Программное обеспечение",
                excerpt = "Kiwix — свободное и открытое ПО для чтения файлов формата ZIM, позволяющее просматривать Википедию офлайн без подключения к Интернету.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Kiwix</h1>
                        <p class="subtitle">Материал из Википедии — свободной энциклопедии</p>

                        <p><strong>Kiwix</strong> — свободная программа для чтения веб-контента в автономном (офлайн) режиме. Программа в первую очередь предназначена для доступа к Википедии без подключения к Интернету, однако поддерживает любые веб-проекты, упакованные в формат <strong>ZIM</strong>.</p>
                        
                        <h2>Основные свойства</h2>
                        <p>Kiwix позволяет скачивать целые копии веб-сайтов (таких как Wikipedia, Wiktionary, TED Talks, Stack Overflow) в сжатом виде на жесткий диск, карту памяти флеш-накопителя или телефон, чтобы затем читать их дома, в самолете, в отдаленных школах или местах лишения свободы.</p>

                        <h2>Формат OpenZIM</h2>
                        <p>OpenZIM является открытым и стандартизированным форматом архивации веб-страниц высокой степени сжатия. Он упаковывает миллионы HTML-файлов, изображений и метаданных в один единственный файл расширения <code>.zim</code>.</p>
                        
                        <div class="highlight-box">
                            <strong>Интересный факт:</strong> Полная русская Википедия со всеми иллюстрациями сжимается в ZIM-файл объемом около 35-40 Гигабайт, а версия без картинок (all nopic) весит менее 2 Гигабайт!
                        </div>

                        <h2>Разделы в Kiwix</h2>
                        <ul>
                            <li><strong>Wikipedia:</strong> Свободная интернет-энциклопедия.</li>
                            <li><strong>Wikiquote:</strong> Коллекция цитат выдающихся людей.</li>
                            <li><strong>StackExchange:</strong> Базы ответов для программистов и инженеров.</li>
                            <li><strong>Project Gutenberg:</strong> Тысячи бесплатных художественных книг.</li>
                        </ul>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_ai",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/AI.html",
                title = "Искусственный интеллект (ИИ)",
                category = "Наука",
                excerpt = "Искусственный интеллект — научная область, занимающаяся созданием интеллектуальных компьютерных систем, имитирующих мыслительные процессы человека.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Искусственный интеллект</h1>
                        <p class="subtitle">Материал из Википедии — свободной энциклопедии</p>

                        <p><strong>Искусственный интеллект (ИИ)</strong> (англ. <em>Artificial Intelligence, AI</em>) — свойство искусственных интеллектуальных систем выполнять творческие функции, которые традиционно считаются прерогативой человека; также — наука и технология создания интеллектуальных машин, особенно интеллектуальных компьютерных программ.</p>

                        <h2>Ключевые направления ИИ</h2>
                        <ol>
                            <li><strong>Машинное обучение (Machine Learning):</strong> Методы построения алгоритмов, способных обучаться на основе опыта.</li>
                            <li><strong>Глубокое обучение (Deep Learning):</strong> Подраздел машинного обучения, использующий многослойные искусственные нейросети для решения сложных задач (распознавание лиц, генерация текста).</li>
                            <li><strong>Обработка естественного языка (NLP):</strong> Понимание, анализ и генерация текстов на человеческих языках.</li>
                            <li><strong>Компьютерное зрение (Computer Vision):</strong> Способность ПК анализировать и извлекать данные из изображений и видео.</li>
                        </ol>

                        <h2>Современный этап развития</h2>
                        <p>В начале 2020-х годов произошел колоссальный скачок благодаря появлению <strong>Больших языковых моделей (LLM)</strong>, таких как семейство моделей Gemini от Google. Эти модели ведут диалоги, пишут программный код и сочиняют стихи на уровне, практически неотличимом от человеческого.</p>

                        <blockquote>
                            «ИИ — одна из самых глубоких технологий, над которыми мы работаем. Она способна изменить медицину, науку и повседневную жизнь людей во всем мире».
                            <cite>— Сундар Пичаи, CEO Google</cite>
                        </blockquote>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_wikipedia",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Wikipedia.html",
                title = "Википедия",
                category = "Культура",
                excerpt = "Википедия — многоязычная универсальная интернет-энциклопедия, создаваемая сообществом добровольцев. Один из самых посещаемых сайтов мира.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Википедия</h1>
                        <p class="subtitle">Материал из Википедии — свободной энциклопедии</p>

                        <p><strong>Википе́дия</strong> — общедоступная многоязычная универсальная интернет-энциклопедия со свободным контентом, реализованная на принципах wiki. Название образовано от слов «вики» (технология создания сайтов) и «энциклопедия».</p>

                        <h2>История проекта</h2>
                        <p>Википедия была официально запущена 15 января 2001 года Джимми Уэйлсом и Ларри Сэнгером. Первоначально она задумывалась как дополнение к Нупедии — энциклопедии со строгой рецензией экспертов.</p>

                        <h2>Основные принципы («Пять столпов»)</h2>
                        <p>Сообщество Википедии руководствуется пятью незыблемыми правилами:</p>
                        <ul>
                            <li>Википедия — это энциклопедия, а не словарь, не трибуна и не каталог.</li>
                            <li>Википедия придерживается нейтральной точки зрения (НТЗ).</li>
                            <li>Материалы Википедии являются свободными для использования и редактирования.</li>
                            <li>Редакторы должны относиться друг к другу с уважением.</li>
                            <li>В Википедии нет строгих правил, мешающих ее улучшению.</li>
                        </ul>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_space",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Space.html",
                title = "Освоение космоса",
                category = "Космонавтика",
                excerpt = "История и перспективы исследования космического пространства человечеством: от первых спутников до марсианских миссий.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Освоение космоса</h1>
                        <p class="subtitle">Материал из Википедии — свободной энциклопедии</p>

                        <p><strong>Освоение космоса</strong> — научно-технический процесс проникновения человечества в космическое пространство за пределы атмосферы Земли с использованием ракетной техники и космических аппаратов.</p>

                        <h2>Ключевые вехи космической эры</h2>
                        <table>
                            <tr>
                                <th>Год</th>
                                <th>Событие</th>
                                <th>Страна</th>
                            </tr>
                            <tr>
                                <td>1957</td>
                                <td>Запуск первого искусственного спутника Земли (Спутник-1)</td>
                                <td>СССР</td>
                            </tr>
                            <tr>
                                <td>1961</td>
                                <td>Первый полет человека в космос (Юрий Гагарин)</td>
                                <td>СССР</td>
                            </tr>
                            <tr>
                                <td>1969</td>
                                <td>Высадка человека на Луну (Нил Армстронг, Базз Олдрин)</td>
                                <td>США</td>
                            </tr>
                            <tr>
                                <td>1998</td>
                                <td>Начало строительства Международной космической станции (МКС)</td>
                                <td>Международный</td>
                            </tr>
                        </table>

                        <h2>Будущие перспективы</h2>
                        <p>Современные планы мировых космических агентств сосредоточены на возвращении человека на Луну в рамках программы Artemis, а также на подготовке пилотируемой миссии на Марс во второй половине 2030-х годов.</p>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_petersburg",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Spb.html",
                title = "Санкт-Петербург",
                category = "География",
                excerpt = "Санкт-Петербург — северная столица России, основанная Петром I в 1703 году. Город каналов, белых ночей и великой архитектуры.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Санкт-Петербург</h1>
                        <p class="subtitle">Материал из Википедии — свободной энциклопедии</p>

                        <p><strong>Санкт-Петербург</strong> (разговорно — <em>Питер</em>, прежние названия — <em>Петроград</em>, <em>Ленинград</em>) — город федерального значения Российской Федерации, расположенный на северо-западе страны, в устье реки Невы на побережье Финского залива.</p>

                        <h2>Основание и величие</h2>
                        <p>Город был заложен царем Петром I 27 мая 1703 года. Санкт-Петербург более двух веков являлся столицей Российской империи. Он прославился прекрасными дворцовыми ансамблями, разводными мостами, обилием рек и Эрмитажем.</p>

                        <h2>Интересные особенности</h2>
                        <ul>
                            <li><strong>Белые ночи:</strong> Уникальный природный феномен, наблюдаемый с конца мая до середины июля, когда солнце опускается за горизонт лишь на несколько градусов.</li>
                            <li><strong>Культурная столица:</strong> В городе расположено более 200 музеев, 80 театров и тысячи исторических зданий под охраной ЮНЕСКО.</li>
                        </ul>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_quantum_physics",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Quantum.html",
                title = "Квантовая физика",
                category = "Физика",
                excerpt = "Раздел физики, изучающий микромир элементарных частиц, свойства материи и излучения на квантовом уровне.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Квантовая физика</h1>
                        <p class="subtitle">Материал из Википедии — свободной энциклопедии</p>

                        <p><strong>Квантовая физика</strong> — теоретическое направление физики, открывшее законы движения и поведения элементарных частиц (электронов, фотонов, кварков). Она родилась в начале XX века, когда классическая механика оказалась неспособна объяснить структуру атома.</p>

                        <h2>Основополагающие законы</h2>
                        <ul>
                            <li><strong>Дуализм волна-частица:</strong> Свет может вести себя одновременно и как непрерывная электромагнитная волна, и как поток дискретных частиц (фотонов).</li>
                            <li><strong>Принцип суперпозиции:</strong> Частица может находиться в нескольких состояниях одновременно, пока не произведено измерение. Знаменитый мысленный эксперимент "Кот Шрёдингера" наглядно демонстрирует это свойство.</li>
                            <li><strong>Квантовая запутанность:</strong> Состояние двух запутанных частиц остается неразрывно связанным независимо от расстояния между ними. Изменение одной мгновенно влияет на другую.</li>
                        </ul>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            )
        )
    }

    private fun getWikiquoteArticles(archiveId: String, archiveTitle: String): List<ArticleEntity> {
        return listOf(
            ArticleEntity(
                id = "${archiveId}_ai_quotes",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/AI_Quotes.html",
                title = "Цитаты об искусственном интеллекте",
                category = "Технологии",
                excerpt = "Коллекция глубоких высказываний ученых, мыслителей и визионеров о будущем искусственного интеллекта и человечества.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Цитаты об искусственном интеллекте</h1>
                        <p class="subtitle">Материал из Викицитатника — свободного сборника цитат</p>

                        <p>Искусственный интеллект — тема вечного спора о прогрессе, угрозах и смысле разума. Ниже собраны высказывания великих умов.</p>

                        <div class="quote-card">
                            <blockquote>
                                «Развитие полноценного искусственного интеллекта может означать конец человеческой расы. Как только люди создадут ИИ, он начнет развиваться самостоятельно и перестраивать себя со все возрастающей скоростью».
                            </blockquote>
                            <p class="author">— Стивен Хокинг, физик-теоретик</p>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Искусственный интеллект — гораздо более опасная вещь, чем ядерное оружие. Запомните мои слова».
                            </blockquote>
                            <p class="author">— Илон Маск, основатель Tesla и SpaceX</p>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Компьютер заслуживает право называться мыслящим, если он может ввести в заблуждение человека, заставив того поверить, что он тоже человек».
                            </blockquote>
                            <p class="author">— Алан Тюринг, математик и дешифровщик</p>
                        </div>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_tolstoy_quotes",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Tolstoy_Quotes.html",
                title = "Афоризмы Льва Толстого",
                category = "Литература",
                excerpt = "Проницательные и вечные афоризмы Льва Николаевича Толстого о жизни, истине, счастье и доброте.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Афоризмы Льва Толстого</h1>
                        <p class="subtitle">Материал из Викицитатника — свободного сборника цитат</p>

                        <p><strong>Лев Николаевич Толстой</strong> (1828—1910) — один из наиболее известных писателей и мыслителей мира, автор романов «Война и мир» и «Анна Каренина».</p>

                        <div class="quote-card">
                            <blockquote>
                                «Счастье не в том, чтобы делать всегда то, что хочешь, а в том, чтобы всегда хотеть того, что делаешь».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Одно из самых удивительных заблуждений — что счастье человека в том, чтобы ничего не делать».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Каждый хочет изменить человечество, но никто не задумывается о том, как изменить себя».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «У меня нет всего, что я люблю. Но я люблю всё, что у меня есть».
                            </blockquote>
                        </div>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_einstein_quotes",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Einstein_Quotes.html",
                title = "Цитаты Альберта Эйнштейна",
                category = "Физика",
                excerpt = "Знаменитые философские изречения великого физика о силе воображения, простоте, тайнах Вселенной и образовании.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Цитаты Альберта Эйнштейна</h1>
                        <p class="subtitle">Материал из Викицитатника — свободного сборника цитат</p>

                        <div class="quote-card">
                            <blockquote>
                                «Воображение важнее, чем знания. Знания ограничены, тогда как воображение охватывает целый мир, стимулируя прогресс».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Есть только два способа прожить жизнь. Первый — как будто чудес не существует. Второй — как будто кругом одни чудеса».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Если вы не можете объяснить это просто, значит, вы сами не понимаете этого до конца».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Безумие — делать одно и то же и каждый раз ожидать другого результата».
                            </blockquote>
                        </div>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_ranevskaya_quotes",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Ranevskaya_Quotes.html",
                title = "Высказывания Фаины Раневской",
                category = "Театр и Кино",
                excerpt = "Знаменитые колкие фразы, остроумный юмор и сарказм великой актрисы театра и кино Фаины Раневской.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Афоризмы Фаины Раневской</h1>
                        <p class="subtitle">Материал из Викицитатника — свободного сборника цитат</p>

                        <p>Фаина Георгиевна Раневская — легендарная советская актриса, чьи эксцентричные фразы разлетелись на крылатые выражения.</p>

                        <div class="quote-card">
                            <blockquote>
                                «Оптимизм — это недостаток информации».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Если больной очень хочет жить, врачи бессильны».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Вторая половинка есть только у мозга, таблетки и задницы. А я изначально целая!».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Жить надо так, чтобы тебя помнили и сволочи».
                            </blockquote>
                        </div>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            ),
            ArticleEntity(
                id = "${archiveId}_jobs_quotes",
                archiveId = archiveId,
                archiveTitle = archiveTitle,
                url = "wiki/Jobs_Quotes.html",
                title = "Цитаты Стива Джобса",
                category = "Бизнес",
                excerpt = "Вдохновляющие мысли основателя Apple Стива Джобса о важности времени, дизайне, инновациях и страсти.",
                htmlContent = """
                    <div class="article-container">
                        <h1>Цитаты Стива Джобса</h1>
                        <p class="subtitle">Материал из Викицитатника — свободного сборника цитат</p>

                        <div class="quote-card">
                            <blockquote>
                                «Ваше время ограничено, поэтому не тратьте его на попытки жить чужой жизнью».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Дизайн — это не то, как вещь выглядит, а то, как она работает».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Оставайтесь голодными. Оставайтесь безрассудными».
                            </blockquote>
                        </div>

                        <div class="quote-card">
                            <blockquote>
                                «Единственный способ делать великие дела — любить то, что вы делаете».
                            </blockquote>
                        </div>
                    </div>
                """.trimIndent(),
                isFeedCandidate = true
            )
        )
    }
}
