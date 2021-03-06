import java.io.FileReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;

import apps.Constants;
import handlers.SentimentAnalysisHandler;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;


public class SemanticAnalasysTest {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("To activate this test, put file path as first argument.");
            System.out.println("for example: /home/yaniv/workSpace/dsps/reviews/B000EVOSE4");
            return;
        }

        String fileName = args[0];
//        test1(fileName);
        test2();


    }

    private static void test2() {
        String text = "Obama 53 Israel";
        try {
            SentimentAnalysisHandler sa = new SentimentAnalysisHandler();
            List<String> entities = sa.getListOfEntities(text);
            System.out.println(entities.toString());

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static void test1(String fileName) {
        JSONParser parser = new JSONParser();
        BufferedReader reader;
        try {
            SentimentAnalysisHandler sa = new SentimentAnalysisHandler();

            reader = new BufferedReader(new FileReader(fileName));
            String line = reader.readLine();
            while (line != null) {
                Object obj = parser.parse(line);
                JSONObject jsonObject = (JSONObject) obj;

                System.out.println(jsonObject.toString());
                JSONArray reviewsList = (JSONArray) jsonObject.get(Constants.REVIEWS);
                for (Object review : reviewsList) {
                    JSONObject jsonReview = (JSONObject) review;
                    String text = (String) jsonReview.get(Constants.TEXT);
                    System.out.println(text);
                    System.out.println("Sentiment Analysis output: " + sa .findSentiment( text));
                    List<String> entities = sa.getListOfEntities(text);
                    System.out.println(entities.toString());
                }

                // read next line
                line = reader.readLine();
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}



















// doc
//        Object obj = parser.parse(new FileReader(fileName ));
//
//        JSONObject jsonObject = (JSONObject) obj;
//            String name = (String) jsonObject.get("Name");
//            String author = (String) jsonObject.get("Author");
//            JSONArray companyList = (JSONArray) jsonObject.get("Company List");
//
//            System.out.println("Name: " + name);
//            System.out.println("Author: " + author);
//            System.out.println("\nCompany List:");
//            Iterator<String> iterator = companyList.iterator();
//            while (iterator.hasNext()) {
//                System.out.println(iterator.next());
//            }

