package ru.hh.school.homework;

import ru.hh.school.homework.common.CachedNaiveSearchTask;
import ru.hh.school.homework.common.NaiveSearchTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.lang.System.currentTimeMillis;
import static java.util.Collections.reverseOrder;
import static java.util.Map.Entry.comparingByValue;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.*;
import static java.util.stream.Collectors.toMap;

// 116613 ms sequential
// 5173 ms cachedPool
// 3671 - fixedThreadPool(50) and 116000 ms sequential
public class L5Cache {

    static ExecutorService executorService = Executors.newFixedThreadPool(50);

    private static final Map<String, String> doubledQueries = new ConcurrentHashMap<>();

    private static final Map<String, Long> CACHE = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException, InterruptedException {
        // Написать код, который, как можно более параллельно:
        // - по заданному пути найдет все "*.java" файлы
        // - для каждого файла вычислит 10 самых популярных слов (см. #naiveCount())
        // - соберет top 10 для каждой папки в которой есть хотя-бы один java файл
        // - для каждого слова сходит в гугл и вернет количество результатов по нему (см. #naiveSearch())
        // - распечатает в консоль результаты в виде:
        // <папка1> - <слово #1> - <кол-во результатов в гугле>
        // <папка1> - <слово #2> - <кол-во результатов в гугле>
        // ...
        // <папка1> - <слово #10> - <кол-во результатов в гугле>
        // <папка2> - <слово #1> - <кол-во результатов в гугле>
        // <папка2> - <слово #2> - <кол-во результатов в гугле>
        // ...
        // <папка2> - <слово #10> - <кол-во результатов в гугле>
        // ...
        //
        // Порядок результатов в консоли не обязательный.
        // При желании naiveSearch и naiveCount можно оптимизировать.

        System.out.println(Runtime.getRuntime().availableProcessors());
        long start = currentTimeMillis();
        Path rootDirPath = Path.of("D:\\projects\\work\\hh-school\\parallelism\\src\\main\\java\\ru\\hh\\school\\parallelism");
        //Path rootDirPath = Path.of("E:\\GSG\\GRI\\frontend\\src\\");
        try (Stream<Path> stream = Files.walk(rootDirPath)) {
            Stream<Path> directoryStream = stream.filter(Files::isDirectory);
            long directorySearchDuration = currentTimeMillis() - start;
            System.out.printf("Directory search is completed in %d ms\r\n", directorySearchDuration);
            directoryStream
                    .forEach(file -> {
                        try {
                            directoryCount(file);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        //TODO: заменить на ожидание когда закончатся СompletableFuture
        sleepUninterruptibly(2, SECONDS);
        doubledQueries
                .entrySet()
                .stream()
                .forEach(entry -> System.out.println(entry.getValue() + " - " + CACHE.get(entry.getKey())));

        executorService.shutdown();

        // waits until all running tasks finish or timeout happens
        boolean finished = executorService.awaitTermination(50000L, TimeUnit.MILLISECONDS);

        if (!finished) {
            // interrupts all running threads, still no guarantee that everything finished
            executorService.shutdownNow();
        }

        long duration = currentTimeMillis() - start;
        System.out.printf("The task completed in %d ms", duration);
    }

    private static void directoryCount(Path path) throws InterruptedException {
        try (Stream<Path> stream = Files.list(path)) {

            Map<String, Long> result = stream
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .map(file -> naiveCount(file))
                    .map(Map::entrySet)
                    .flatMap(Collection::stream)
                    .collect(groupingBy(Map.Entry::getKey, summarizingLong(Map.Entry::getValue)))
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, wordCount -> wordCount.getValue().getSum()))
                    .entrySet()
                    .stream()
                    .sorted(comparingByValue(reverseOrder()))
                    .limit(10)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            // такой кеш работает, но надо дождаться когда все потоки сходят в гугл и вывести повторяющиеся запросы
            result.forEach((key, value) -> {
                String keyInLowerCase = key.toLowerCase();
                Long count = CACHE.get(key.toLowerCase());
                if (count == null) {
                    CACHE.put(keyInLowerCase, -2L);
                    executorService.execute(new NaiveSearchTask(key, path));
                } else {
                    doubledQueries.put(keyInLowerCase, path + " - " + keyInLowerCase);
                }
            });

        } catch (IOException e) {
            System.out.println("It is impossible to get count value form google");
        }
    }

    private static Map<String, Long> naiveCount(Path path) {
        try {
            return Files.lines(path)
                    .flatMap(line -> Stream.of(line.split("[^a-zA-Z0-9]")))
                    .filter(word -> word.length() > 3)
                    .collect(groupingBy(identity(), counting()))
                    .entrySet()
                    .stream()
                    .sorted(comparingByValue(reverseOrder()))
                    .limit(10)
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
