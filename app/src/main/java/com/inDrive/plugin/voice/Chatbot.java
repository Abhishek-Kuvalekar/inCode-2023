package com.inDrive.plugin.voice;

import android.content.Context;
import android.util.Log;

import com.inDrive.plugin.entities.Driver;
import com.inDrive.plugin.entities.Location;
import com.inDrive.plugin.entities.LocationCoordinate;
import com.inDrive.plugin.entities.Passenger;
import com.inDrive.plugin.entities.Ride;
import com.inDrive.plugin.entities.Vehicle;
import com.inDrive.plugin.navigation.NavigationProvider;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import opennlp.tools.doccat.BagOfWordsFeatureGenerator;
import opennlp.tools.doccat.DoccatFactory;
import opennlp.tools.doccat.DoccatModel;
import opennlp.tools.doccat.DocumentCategorizerME;
import opennlp.tools.doccat.DocumentSample;
import opennlp.tools.doccat.DocumentSampleStream;
import opennlp.tools.doccat.FeatureGenerator;
import opennlp.tools.lemmatizer.LemmatizerME;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.InputStreamFactory;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.TrainingParameters;
import opennlp.tools.util.model.ModelUtil;

public class Chatbot {
    enum Question {
        NULL, GREETING, CONFIRM_SRC_DEST, USE_CURRENT_LOCATION, SPECIFY_DEST, CONFIRM_DEST, UPDATE_DEST_QUESTION, SPECIFY_SOURCE, CONFIRM_SOURCE, SURE_CANCEL, UPDATE_SOURCE_QUESTION
    }
    static final String GREETING = "greeting";
    static final String BOOK_CAB_INSTR = "book_cab_instruction";
    static final String LOCATION_INQUIRY = "location_inquiry";
    static final String DRIVER_INQUIRY = "driver_inquiry";
    static final String VEHICLE_INQUIRY = "vehicle_inquiry";
    static final String TIME_FOR_DRIVER = "time_for_driver";
    static final String TIME_TO_REACH = "time_to_reach";
    static final String CHANGE_SOURCE = "change_source";
    static final String CHANGE_DESTINATION = "change_destination";
    static final String OTP_INQUIRY = "otp_inquiry";
    static final String START_RIDE = "start_instruction";
    static final String ALL_GOOD = "all_okay";
    static final String RATING = "stars";
    static final String AFFIRMATION = "affirmation";
    static final String NEGATION = "negation";
    static final String CALL_DRIVER = "call_driver";
    static final String STOP_PROCESS = "stop_process";
    static final String CANCEL_RIDE = "cancel_ride";
    private Context context;

    private Location source;
    private Location dest;

    private Ride ride;
    private Question last_question;
    private DoccatModel model;
    static DocumentCategorizerME myDocCategorizer;
    static SentenceDetectorME mySenCategorizer;
    static TokenizerME tokenizer;
    static POSTaggerME myPOSCategorizer;
    static LemmatizerME myLemCategorizer;

    private List<String> properNouns;
    private String[] tokens;
    private ExecutorService executorService = Executors.newFixedThreadPool(5);

    private AtomicInteger initializerCount;

    private NavigationProvider navigationProvider;

    public Chatbot(Context context, Passenger passenger) throws InterruptedException {
        this.context = context;
        last_question = Question.NULL;
        ride = new Ride(passenger);
        navigationProvider = new NavigationProvider(context);
        System.setProperty("org.xml.sax.driver", "org.xmlpull.v1.sax2.Driver");
        initializerCount = new AtomicInteger(0);
        try {
            model = trainCategorizerModel();
            initializeDocumentCategorizer();
            initializerCount.addAndGet(1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        executorService.submit(() -> {
            try {
                initializeSentenceModel();
                initializerCount.addAndGet(1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        executorService.submit(() -> {
            try {
                initializePOSModel();
                initializerCount.addAndGet(1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        executorService.submit(() -> {
            try {
                initializeTokenizerModel();
                initializerCount.addAndGet(1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        executorService.submit(() -> {
            try {
                initializeLemmatizer();
                initializerCount.addAndGet(1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void initializeSentenceModel() throws IOException {
        InputStream is = null;
        SentenceModel sm;
        try {
            is = context.getAssets().open("en_sent.bin");
            sm = new SentenceModel(is);
            mySenCategorizer = new SentenceDetectorME(sm);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    public boolean isInitialized() {
        return (initializerCount.get() == 5);
    }

    private void initializeTokenizerModel() throws IOException {
        InputStream is = null;
        TokenizerModel tm;
        try {
            is =  context.getAssets().open("en_token.bin");
            tm = new TokenizerModel(is);
            tokenizer = new TokenizerME(tm);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }
    private void initializePOSModel() throws IOException {
        InputStream is = null;
        try {
            is =  context.getAssets().open("en_pos_maxent.bin");
            // Initialize POS tagger tool
            myPOSCategorizer = new POSTaggerME(new POSModel(is));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }
    private void initializeLemmatizer() throws IOException {
        InputStream is = null;
        try {
            is =  context.getAssets().open("en_lemmatizer.bin");
            // Tag sentence.
            myLemCategorizer = new LemmatizerME(new LemmatizerModel(is));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    private void initializeDocumentCategorizer() throws IOException {
        // Initialize document categorizer tool
        myDocCategorizer = new DocumentCategorizerME(model);
    }

    public String getResponse(String input) throws IOException {
        if(myDocCategorizer == null || tokenizer ==null || myLemCategorizer == null || myPOSCategorizer ==null || mySenCategorizer == null) {
            return " ";
        }
        // Break users chat input into sentences using sentence detection.
        String[] sentences = breakSentences(input);
        String answer = "";
        boolean conversationComplete = false;

        // Loop through sentences.
        for (String sentence : sentences) {

            // Separate words from each sentence using tokenizer.
            tokens = tokenizeSentence(sentence);

            // Tag separated words with POS tags to understand their gramatical structure.
            String[] posTags = detectPOSTags(tokens);

            properNouns = new ArrayList<>();// = Arrays.stream(tokens).filter((s) -> posTags[i]=="NNP").toArray(String[]::new);

            for(int i=0; i<tokens.length; i++) {
                if(posTags[i].equals("NNP")) {
                    properNouns.add(tokens[i].toLowerCase());
                }
                if(tokens.length <= 2 && posTags[i].equals("NN")) {
                    properNouns.add(tokens[i].toLowerCase());
                }
            }
            // Lemmatize each word so that its easy to categorize.
            String[] lemmas = lemmatizeTokens(tokens, posTags);

            // Determine BEST category using lemmatized tokens used a mode that we trained
            // at start.
            String category = detectCategory(model, lemmas);

            // Get predefined answer from given category & add to answer.
            answer = processInstruction(category, input);

            // If category conversation-complete, we will end chat conversation.
            if ("conversation-complete".equals(category)) {
                conversationComplete = true;
            }
        }

        return answer;
    }

    /**
     * Train categorizer model as per the category sample training data we created.
     *
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    private DoccatModel trainCategorizerModel() throws FileNotFoundException, IOException {
        // faq-categorizer.txt is a custom training data with categories as per our chat
        // requirements.
        InputStream is = null;
        ObjectStream<DocumentSample> sampleStream = null;
        ObjectStream<String> lineStream = null;
        try {
            is =  context.getAssets().open("faq_categorizer.txt");
            InputStream finalIs = is;
            InputStreamFactory inputStreamFactory = () -> finalIs;
            lineStream = new PlainTextByLineStream(inputStreamFactory, StandardCharsets.UTF_8);
            sampleStream = new DocumentSampleStream(lineStream);

            DoccatFactory factory = new DoccatFactory(new FeatureGenerator[]{new BagOfWordsFeatureGenerator()});

            TrainingParameters params = ModelUtil.createDefaultTrainingParameters();
            params.put(TrainingParameters.CUTOFF_PARAM, 0);

            // Train a model with classifications from above file.
            DoccatModel model = DocumentCategorizerME.train("en", sampleStream, params, factory);
            return model;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (lineStream != null) {
                lineStream.close();
            }
            if (sampleStream != null) {
                sampleStream.close();
            }
            if (is != null) {
                is.close();
            }

        }

    }

    /**
     * Detect category using given token. Use categorizer feature of Apache OpenNLP.
     *
     * @param model
     * @param finalTokens
     * @return
     * @throws IOException
     */
    private static String detectCategory(DoccatModel model, String[] finalTokens) throws IOException {

        // Get best possible category.
        double[] probabilitiesOfOutcomes = myDocCategorizer.categorize(finalTokens);
        String category = myDocCategorizer.getBestCategory(probabilitiesOfOutcomes);
        System.out.println("Category: " + category);

        return category;

    }

    /**
     * Break data into sentences using sentence detection feature of Apache OpenNLP.
     *
     * @param data
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    private String[] breakSentences(String data) throws FileNotFoundException, IOException {
        String[] sentences = mySenCategorizer.sentDetect(data);

        System.out.println("Sentence Detection: " + Arrays.stream(sentences).collect(Collectors.joining(" | ")));

        return sentences;
    }

    /**
     * Break sentence into words & punctuation marks using tokenizer feature of
     * Apache OpenNLP.
     *
     * @param sentence
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    private String[] tokenizeSentence(String sentence) throws FileNotFoundException, IOException {
        String[] tokens = tokenizer.tokenize(sentence);
        System.out.println("Tokenizer : " + Arrays.stream(tokens).collect(Collectors.joining(" | ")));
        return tokens;
    }

    /**
     * Find part-of-speech or POS tags of all tokens using POS tagger feature of
     * Apache OpenNLP.
     *
     * @param tokens
     * @return
     * @throws IOException
     */
    private String[] detectPOSTags(String[] tokens) throws IOException {
        // Tag sentence.
        String[] posTokens = myPOSCategorizer.tag(tokens);
        System.out.println("POS Tags : " + Arrays.stream(posTokens).collect(Collectors.joining(" | ")));
        return posTokens;
    }

    /**
     * Find lemma of tokens using lemmatizer feature of Apache OpenNLP.
     *
     * @param tokens
     * @param posTags
     * @return
     * @throws IOException
     */
    private String[] lemmatizeTokens(String[] tokens, String[] posTags)
            throws IOException {

        String[] lemmaTokens = myLemCategorizer.lemmatize(tokens, posTags);
        System.out.println("Lemmatizer : " + Arrays.stream(lemmaTokens).collect(Collectors.joining(" | ")));
        return lemmaTokens;
    }

    public String processInstruction(String category, String instruction) {
        Log.i("INSTR TYPE ", last_question.toString());
        String response = "";

        if(last_question == Question.SPECIFY_DEST || last_question == Question.UPDATE_DEST_QUESTION) {
            dest = fetchLocationFromText(instruction);
            if(dest != null) {
                last_question = Question.CONFIRM_DEST;
                return "Are you sure you want to change the destination to "+dest.getLocationName()+"?";
            }
        }
        if(last_question == Question.SPECIFY_SOURCE  || last_question == Question.UPDATE_SOURCE_QUESTION) {
            source = fetchLocationFromText(instruction);
            if(source != null) {
                last_question = Question.CONFIRM_SOURCE;
                return "Are you sure you want to change the pickup to "+source.getLocationName()+"?";
            }
        }

        switch(category) {
            case GREETING:
                response = "Hey there! How can I help you?";
                last_question = Question.GREETING;
                break;
            case BOOK_CAB_INSTR:
                response =  processBookCabCommand(instruction);
                break;
            case LOCATION_INQUIRY:
                response =  processLocationInquiry();
                break;
            case DRIVER_INQUIRY :
                response =  processDriverInquiry();
                break;
            case VEHICLE_INQUIRY :
                response =  processVehicleInquiry();
                break;
            case TIME_FOR_DRIVER :
                if(ride.getRideStatus() == "BOOKED") {
                    response = ride.getDriver().getDriverName()+ " will arrive in "+ride.getTimeInMinutesForDriver()+" minutes.";
                }
                else if(ride.getRideStatus() == "DRIVER_ARRIVED"){
                    response = ride.getDriver().getDriverName()+" has arrived at the pickup.";
                }
                else response = "";
                break;
            case TIME_TO_REACH:
                if(ride.getRideStatus() != "NOT_BOOKED") {
                    response = "You will reach the destination in "+ride.getTimeInMinutesToReachDest()+" minutes.";
                }
                else response = "";
                break;
            case CHANGE_SOURCE :
                response = processChangeSource(instruction);
                break;
            case CHANGE_DESTINATION :
                response = processChangeDestination(instruction);
                break;
            case OTP_INQUIRY :
                response = "Your OTP is One Two Three Four.";
                break;
            case START_RIDE:
                ride.setRideStatus("STARTED");
                response = "Ride started. Enjoy your journey!";
                break;
            case ALL_GOOD :
                response = "Okay";
                break;
            case RATING :
                ride.getDriver().setStars(fetchRatingFromString(instruction));
                response = "Thanks";
                break;
            case AFFIRMATION:
                if(last_question.equals(Question.CONFIRM_SRC_DEST)) {
                    ride.setSource(source);
                    ride.setDestination(dest);
                    if(ride.getRideStatus() == "NOT_BOOKED")
                    {
                        response = bookRide();
                    }
                    else {
                        response = "Pick up and drop locations updated.";
                    }
                }
                else if(last_question == Question.UPDATE_DEST_QUESTION) {
                    last_question = Question.SPECIFY_DEST;
                    response = "Please specify the updated destination";
                }
                else if(last_question == Question.UPDATE_SOURCE_QUESTION) {
                    last_question = Question.SPECIFY_SOURCE;
                    response = "Please specify the updated pickup";
                }
                else if(last_question == Question.CONFIRM_DEST) {
                    ride.setDestination(dest);
                    textToSpeech("Successfully updated destination to "+ dest.getLocationName());
                    response = confirmSourceAndDest();
                }
                else if(last_question == Question.CONFIRM_SOURCE) {
                    ride.setDestination(dest);
                    textToSpeech("Successfully updated pickup to "+ source.getLocationName());
                    response = confirmSourceAndDest();
                }
                else if(last_question.equals(Question.USE_CURRENT_LOCATION)) {
                    source = getCurrentLocation();
                    textToSpeech("Successfully updated pickup to "+ source.getLocationName());
                    response = confirmSourceAndDest();
                }
                else if(last_question.equals(Question.SURE_CANCEL)) {
                    response = cancelRide();
                }
                break;
            case NEGATION:
                if(last_question.equals(Question.CONFIRM_SRC_DEST)) {
                    last_question = Question.UPDATE_DEST_QUESTION;
                    if(dest != null) {
                        response = "Do you want to update the destination from " + dest.getLocationName();
                    }
                    else {
                        response = "Do you want to update the destination?";
                    }
                }
                else if(last_question == Question.UPDATE_DEST_QUESTION) {
                    last_question = Question.UPDATE_SOURCE_QUESTION;
                    if(source != null) {
                        response = "Do you want to update the pickup from " + source.getLocationName();
                    }
                    else {
                        response = "Do you want to update the pickup?";
                    }
                }
                else if(last_question == Question.UPDATE_SOURCE_QUESTION) {
                    response = confirmSourceAndDest();
                }
                else if(last_question == Question.CONFIRM_DEST) {
                    ride.setDestination(dest);
                    textToSpeech("Successfully updated destination to "+ dest.getLocationName());
                    response = confirmSourceAndDest();
                }
                else if(last_question == Question.CONFIRM_SOURCE) {
                    ride.setDestination(dest);
                    textToSpeech("Successfully updated pickup to "+ dest.getLocationName());
                    response = confirmSourceAndDest();
                }
                break;
            case CANCEL_RIDE:
                response = "Are you sure you want to cancel the ride?";
                last_question = Question.SURE_CANCEL;
                break;
            case CALL_DRIVER:
                response = callDriver();
                break;
            case STOP_PROCESS:
                break;
            default:
                response = "Sorry. Could you repeat?";
                break;

        }
        return response;
    }

    private String callDriver() {
        return "calling driver at " + ride.getDriver().getDriverContact();

    }

    private String cancelRide() {
        ride.setDriver(null);
        ride.setSource(null);
        ride.setDestination(null);
        ride.setTimeInMinutesToReachDest(0);
        ride.setTimeInMinutesForDriver(0);
        ride.setRideStatus("NOT_BOOKED");
        return "Ride cancelled successfully.";
    }

    private int fetchRatingFromString(String instruction) {
        return 4;
    }

    private Location getCurrentLocation() {
        Optional<Location> locationOptional = navigationProvider.getCurrentLocation();

        if (!locationOptional.isPresent())
            return null;

        return locationOptional.get();
    }

    private void textToSpeech(String s) {
    }

    private Location fetchLocationFromText(String instruction) {
        Location loc;
        String locStr = "";
        if(properNouns.isEmpty()) {
            return null;
        }
        for(String noun: properNouns) {
            locStr += " " + noun;
        }
        loc = getLocation(locStr);
        if( loc != null) {
            Log.i("Loc = ", loc.getLocationName());
            return loc;
        }
        return null;
    }

    private Location getLocation(String noun) {
        Optional<Location> locationOptional = navigationProvider.getLocation(noun);

        if (!locationOptional.isPresent())
            return null;

        return locationOptional.get();
    }

    private String bookRide() {
        Vehicle vehicle = new Vehicle("MH12 1234", "Mini", "Celerio");
        Driver driver = new Driver("Dilip", vehicle, "212211", 5);
        ride.setDriver(driver);
        ride.setRideStatus("BOOKED");
        ride.setTimeInMinutesForDriver(4);
        ride.setTimeInMinutesToReachDest(15);
        return "Ride successfully booked";
    }

    private String processChangeDestination(String instruction) {
        Location loc = fetchLocationFromText(instruction);
        if(loc != null) {
            last_question = Question.CONFIRM_DEST;
            dest = loc;
            return "Do you want to change the drop to "+loc.getLocationName()+"?";
        }
        last_question = Question.UPDATE_DEST_QUESTION;
        return "Are you sure you want to update the destination from "+dest.getLocationName();
    }

    private String processChangeSource(String instruction) {
        Location loc = fetchLocationFromText(instruction);
        if(loc != null) {
            last_question = Question.CONFIRM_SOURCE;
            source = loc;
            return "Do you want to change the pickup to "+loc.getLocationName()+"?";
        }
        last_question = Question.UPDATE_SOURCE_QUESTION;
        return "Are you sure you want to update the source from "+source.getLocationName();
    }

    private String processVehicleInquiry() {
        return "vehicle details";
    }

    private String processDriverInquiry() {
        return "driver details";
    }

    private String processLocationInquiry() {
        return "curr location";
    }

    private String processBookCabCommand(String instruction) {
        source = fetchSourceLocationFromInstr(instruction);
        dest = fetchDropLocationFromInstr(instruction);
        return confirmSourceAndDest();
    }

    String confirmSourceAndDest() {
        if(dest == null) {
            last_question = Question.USE_CURRENT_LOCATION;
            return "Do you want to use your current location as the pickup?";
        }
        if(source == null) {
            last_question = Question.SPECIFY_DEST;
            return "Please specify destination";
        }
        last_question = Question.CONFIRM_SRC_DEST;
        return "Request to book a cab from "+source.getLocationName()+" to "+dest.getLocationName()+" received. Do you want to look for nearby rides?";
    }
    private Location fetchSourceLocationFromInstr(String instruction) {
            for(int i=0; i<tokens.length; i++) {
                if(tokens[i].equals("from") && properNouns.contains(tokens[i+1].toLowerCase())) {
                    String locString = tokens[i+1];
                    i++;
                    while(i+1<tokens.length && properNouns.contains(tokens[i+1].toLowerCase())) {
                        locString += " "+tokens[i+1];
                    }
                    Log.i("Source = ", locString);
                    return getLocation(locString);
                }
            }
            return null;
    }

    private Location fetchDropLocationFromInstr(String instruction) {
        for(int i=0; i<tokens.length; i++) {
            if(tokens[i].equals("to") && properNouns.contains(tokens[i+1].toLowerCase())) {
                String locString = tokens[i+1];
                i++;
                while(i+1<tokens.length && properNouns.contains(tokens[i+1].toLowerCase())) {
                    locString += " "+tokens[i+1];
                }
                Log.i("Dest = ", locString);
                return getLocation(locString);
            }
        }
        return null;
    }
}
