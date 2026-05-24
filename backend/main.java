import java.io.*;
import java.net.http.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

public class KisanMitraBackend {

    static String PERPLEXITY_API_KEY = System.getenv("PERPLEXITY_API_KEY");
    static String HF_API_KEY = System.getenv("HUGGINGFACE_API_KEY");

    public static Map<String, Object> extractCleanJson(String textResponse) {
        Map<String, Object> result = new HashMap<>();

        if (textResponse == null || textResponse.isEmpty()) {
            result.put("summary", "No response.");
            return result;
        }

        String cleaned = textResponse
                .replace("```json", "")
                .replace("```", "")
                .trim();

        try {
            Pattern pattern = Pattern.compile("(\\{.*\\})", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(cleaned);

            if (matcher.find()) {
                JSONObject json = new JSONObject(matcher.group(1));

                if (json.has("summary")) {
                    String summary = json.getString("summary")
                            .replace("**", "")
                            .replace("### ", "")
                            .replace("###", "")
                            .trim();

                    json.put("summary", summary);
                }

                return json.toMap();
            }

            result.put("summary", cleaned);

        } catch (Exception e) {
            result.put("summary", cleaned);
        }

        return result;
    }

    public static String callHuggingFaceVision(byte[] imageBytes) {

        if (HF_API_KEY == null) {
            return "Farm image";
        }

        try {
            HttpClient client = HttpClient.newHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api-inference.huggingface.co/models/microsoft/git-base"))
                    .header("Authorization", "Bearer " + HF_API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {

                JSONArray arr = new JSONArray(response.body());

                return arr.getJSONObject(0)
                        .optString("generated_text", "Farm image");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return "Farm image";
    }

    public static Map<String, Object> callPerplexityAPI(
            String systemInstruction,
            String userQuery
    ) {

        Map<String, Object> result = new HashMap<>();

        try {

            HttpClient client = HttpClient.newHttpClient();

            JSONObject payload = new JSONObject();

            payload.put("model", "sonar-pro");
            payload.put("temperature", 0.1);

            JSONArray messages = new JSONArray();

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemInstruction);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userQuery);

            messages.put(systemMsg);
            messages.put(userMsg);

            payload.put("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.perplexity.ai/chat/completions"))
                    .header("Authorization", "Bearer " + PERPLEXITY_API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {

                result.put("summary", "API Error");

                return result;
            }

            JSONObject jsonResponse = new JSONObject(response.body());

            String content = jsonResponse
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

            result = extractCleanJson(content);

            if (!result.containsKey("sources")) {
                result.put("sources", new ArrayList<>());
            }

            return result;

        } catch (Exception e) {

            result.put("summary", "Connection failed.");

            return result;
        }
    }

    public static void main(String[] args) throws Exception {

        Scanner sc = new Scanner(System.in);

        System.out.println("Enter Query:");
        String query = sc.nextLine();

        System.out.println("Enter Language (en/hi/ml):");
        String language = sc.nextLine();

        Map<String, String> langMap = new HashMap<>();
        langMap.put("hi", "Hindi");
        langMap.put("ml", "Malayalam");
        langMap.put("en", "English");

        String targetLang = langMap.getOrDefault(language, "English");

        String finalQuery = query;

        System.out.println("Do you want to add image? (yes/no)");
        String imgChoice = sc.nextLine();

        if (imgChoice.equalsIgnoreCase("yes")) {

            System.out.println("Enter image path:");

            String imgPath = sc.nextLine();

            byte[] imageData = Files.readAllBytes(Paths.get(imgPath));

            String desc = callHuggingFaceVision(imageData);

            finalQuery = "IMAGE SHOWS: " + desc +
                    ". QUESTION: " + query;
        }

        String systemInstruction;

        if (query.contains("TRANSLATE_THIS_TEXT")) {

            String textToTranslate = query.replace(
                    "TRANSLATE_THIS_TEXT:",
                    ""
            );

            systemInstruction = """
                    You are a translation engine.
                    Target Language: %s

                    STRICT RULES:
                    1. Keep the headers "CONCEPT:", "HOW IT WORKS:", "SOLUTION:" exactly in ENGLISH.
                    2. Translate ALL content text into %s script.
                    3. Do NOT explain the translation.
                    4. Do NOT use English alphabets in the body text.

                    Example Output format:

                    CONCEPT:
                    (Translated Text)

                    HOW IT WORKS:
                    - (Translated Point)
                    """.formatted(targetLang, targetLang);

            finalQuery = textToTranslate;

        } else {

            systemInstruction = """
                    You are Kisan Mitra, a village agricultural expert who speaks %s fluently.

                    TASK:
                    Answer the farmer's question using %s Script ONLY.

                    FORMATTING RULES:
                    1. Use exact headers:
                       CONCEPT:
                       HOW IT WORKS:
                       SOLUTION:

                    2. Content under headers must be in %s.
                    3. No English explanation.

                    Output JSON:
                    {
                      "topic": "Title",
                      "summary": "Structured response",
                      "sources": []
                    }
                    """.formatted(
                    targetLang,
                    targetLang,
                    targetLang
            );
        }

        Map<String, Object> response = callPerplexityAPI(
                systemInstruction,
                finalQuery
        );

        System.out.println("\n===== RESPONSE =====");
        System.out.println(response);
    }
}
