package one.demo.challenge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "one.demo.challenge.level11")
public class ChallengeMain {
    public static void main(String[] args) {
        SpringApplication.run(ChallengeMain.class, args);
    }
}
