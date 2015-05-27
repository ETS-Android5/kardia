package org.lightsys.donorapp;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lightsys.donorapp.data.Account;
import org.lightsys.donorapp.data.Fund;
import org.lightsys.donorapp.data.Gift;
import org.lightsys.donorapp.data.LocalDBHandler;
import org.lightsys.donorapp.data.PrayerRequest;
import org.lightsys.donorapp.data.Update;
import org.lightsys.donorapp.data.Year;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;

/**
 * This class is used to pull json files (from the urls above)
 * for every account and then format and store the formated data into the
 * sqlite database
 *
 * @author Andrew Cameron
 *
 */
public class DataConnection extends AsyncTask<String, Void, String> {

    private Account account;
    private String Host_Name;
    private int Donor_ID;
    private String Password;
    private String AccountName;
    private int Account_ID;
    private Context dataContext;
    private LocalDBHandler db;
    AccountsActivity.ErrorType errorType = null;

    private static final String Tag = "DPS";

    public DataConnection(Context context, Account a) {
        super();
        dataContext = context;
        account = a;
    }

    private boolean isValidAccount(Account a) {
        boolean isValid = false;
        Host_Name = a.getServerName();
        Donor_ID = a.getDonorid();
        Password = a.getAccountPassword();
        AccountName = a.getAccountName();

        try {
            String test = GET("http://" + Host_Name + ":800/apps/kardia/api/donor/" + Donor_ID +
                    "/?cx__mode=rest&cx__res_type=collection&cx__res_format=attrs&cx__res_attrs=basic");
            // Unauthorized signals invalid ID
            // 404 not found signals incorrect username or password
            // Empty or null signals an incorrect server name
            if (test.equals("") || test == null) {
                errorType = AccountsActivity.ErrorType.Server;
            } else if (!test.equals("<H1>Unauthorized</H1>")) {
                errorType = AccountsActivity.ErrorType.Unauthorized;
            } else if (!test.contains("404 Not Found")) {
                errorType = AccountsActivity.ErrorType.NotFound;
            } else {
                isValid = true;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return isValid;
    }

    @Override
    protected String doInBackground(String... params) {
        try{
            DataPull();
        }catch(Exception e){
            Log.w(Tag, "The DataPull failed. (probably not connected to internet or vmplayer): "
                    + e.getLocalizedMessage());
        }
        return null;
    }

    @Override
    protected void onPostExecute(String params){

    }

    //Data formating and storing methods Below

    //For All Funds: (related to account(s))
    // "http://" + Host_Name + ":800/apps/kardia/api/donor/" + Donor_ID + "/Funds?cx__mode=rest&cx__res_type=collection&cx__res_format=attrs&cx__res_attrs=basic"

    //For All Years donated (to single Fund)
    // "http://" + Host_Name + ":800/apps/kardia/api/donor/" + Donor_ID + "/Years?cx__mode=rest&cx__res_format=attrs&cx__res_type=collection&cx__res_attrs=basic"

    //For All Related Years (to single Fund) (this is used in loop):
    // "http://" + Host_Name + ":800/apps/kardia/api/donor/" + Donor_ID + "/Funds/" + Fund_Name + "/Years?cx__mode=rest&cx__res_format=attrs&cx__res_type=collection&cx__res_attrs=basic"

    //For All Related Gifts (inside of single year with-in loop):
    // "http://" + Host_Name + ":800/apps/kardia/api/donor/" + Donor_ID + "/Funds/" + Fund_Name + "/Years/" + Year + "/Gifts?cx__mode=rest&cx__res_format=attrs&cx__res_type=collection&cx__res_attrs=basic"

    /**
     * This method runs through each account stored to pull info
     */
    private void DataPull(){
        db = new LocalDBHandler(dataContext, null, null, 9);
        Host_Name = account.getServerName();
        Donor_ID = account.getDonorid();
        Password = account.getAccountPassword();
        AccountName = account.getAccountName();
        Account_ID = account.getId();
        boolean validAccount = true;


        //If account does not exist in the database, check to see if it is a valid account
        //Set the validation field in the respective class that account is being tested in
        ArrayList<Account> databaseAccounts = db.getAccounts();
        if (!databaseAccounts.contains(account)) {
            validAccount = isValidAccount(account);
            if (dataContext.getClass() == AccountsActivity.class) {
                if (!validAccount) {
                    AccountsActivity.setErrorType(errorType);
                }
                AccountsActivity.setValidation(validAccount);
            } else if (dataContext.getClass() == EditAccountActivity.class) {
                if (!validAccount) {
                    EditAccountActivity.setErrorType(errorType);
                }
                EditAccountActivity.setValidation(validAccount);
            }
        }
        if(!validAccount) {
            return;
        }
        loadNotes(account);

        loadYears(GET("http://" + Host_Name + ":800/apps/kardia/api/donor/" + Donor_ID +
                "/Years?cx__mode=rest&cx__res_format=attrs&cx__res_type=collection&cx__res_attrs=basic"));

        loadFunds(GET("http://" + Host_Name + ":800/apps/kardia/api/donor/" + Donor_ID +
                "/Funds?cx__mode=rest&cx__res_format=attrs&cx__res_type=collection&cx__res_attrs=basic"));

        for(Fund f : db.getFundsForAccount(Account_ID)){

            String Fund_Name = "";
            try {
                Fund_Name = URLEncoder.encode(f.getFullName(), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            int fundid = f.getID();

            loadFundYears(GET("http://" + Host_Name + ":800/apps/kardia/api/donor/" + Donor_ID + "/Funds/"
                    + Fund_Name + "/Years?cx__mode=rest&cx__res_format=attrs&cx__res_type=collection&cx__res_attrs=basic"), fundid);

            for(Year y : db.getYears(fundid)){
                int yearid = y.getId();
                String Year = y.getName();

                loadGifts(GET("http://" + Host_Name + ":800/apps/kardia/api/donor/" + Donor_ID + "/Funds/"
                                + Fund_Name + "/Years/" + Year + "/Gifts?cx__mode=rest&cx__res_format=attrs&cx__res_type=collection&cx__res_attrs=basic"),
                        yearid, fundid);
            }
        }
        // If no timestamp found, add timestamp, otherwise update timestamp
        long originalStamp = db.getTimeStamp();
        if (originalStamp == -1) {
            db.addTimeStamp("" + Calendar.getInstance().getTimeInMillis());
        } else {
            long currentStamp = Calendar.getInstance().getTimeInMillis();
            db.updateTimeStamp("" + originalStamp, "" + currentStamp);
        }
        db.close();
    }

    /**
     * Attempts to do basic Http Authentication, and send a get request from the url
     *
     * @param url for get request.
     * @return string results of the query.
     */
    public String GET(String url) {
        InputStream inputStream = null;
        String result = "";

        try {

            CredentialsProvider credProvider = new BasicCredentialsProvider();
            credProvider.setCredentials(new AuthScope(Host_Name, 800),
                    new UsernamePasswordCredentials(AccountName, Password));

            DefaultHttpClient client = new DefaultHttpClient();

            client.setCredentialsProvider(credProvider);

            HttpResponse response = client.execute(new HttpGet(url));

            inputStream = response.getEntity().getContent();

            if (inputStream != null) {
                result = convertInputStreamToString(inputStream);
            } else {
                result = "Did not work.";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * If there are results, change them into a string.
     *
     * @param in, the inputStream containing the results of the query (if any)
     * @return a string with the results of the query.
     * @throws IOException
     */
    private String convertInputStreamToString(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line = "", result = "";

        while ((line = reader.readLine()) != null) {
            result += line;
        }
        in.close();
        return result;
    }

    /**
     * formats the result string into funds and
     * if a fund was not stored yet, adds it to
     * the local sqlite database in a fund table
     * and it also adds a relation between the fund and the specific account
     * (Notice, only adds the fund and/or relationship if one does not already exist)
     *
     * @param result
     * @throws JSONException
     */
    public void loadFunds(String result) {

        ArrayList<String> TestFundNamesList = db.getFundNames(Account_ID);
        JSONObject json = null;
        try{
            json = new JSONObject(result);
        }catch(JSONException e){
            e.printStackTrace();
        }
        JSONArray tempFunds = json.names();

        for (int x = 0; x < tempFunds.length(); x++) {
            try {
                if(!tempFunds.getString(x).equals("@id")){
                    JSONObject fundObj = json.getJSONObject(tempFunds.getString(x));
                    if (!TestFundNamesList.contains(fundObj.getString("name"))) {
                        JSONObject giftObj = fundObj.getJSONObject("gift_total");
                        int[] gifttotal = {
                                Integer.parseInt(giftObj.getString("wholepart")),
                                Integer.parseInt(giftObj.getString("fractionpart"))
                        };
                        int giftcount = Integer.parseInt(fundObj.getString("gift_count"));

                        Fund temp = new Fund();
                        temp.setName(fundObj.getString("fund"));
                        temp.setFullName(fundObj.getString("name"));
                        temp.setFund_desc(fundObj.getString("fund_desc"));
                        temp.setGift_count(giftcount);
                        temp.setGift_total(gifttotal);
                        temp.setGiving_url(fundObj.getString("giving_url"));

                        db.addFund(temp);
                        int fundId = db.getLastId("fund");
                        db.addFund_Account(fundId, Account_ID);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void loadNotes(Account account)
    {
        try{
            String supporterID = ""+account.getDonorid();
            String missionaryJSON = GET("http://" + Host_Name+ ":800/apps/kardia/api/supporter/"+supporterID+"/Missionaries?cx__mode=rest&cx__res_type=collection");
            if(!missionaryJSON.contains("404")) {
                JSONObject missionaries = new JSONObject(missionaryJSON);
                Iterator<String> missionaryIDs = missionaries.keys();
                while (missionaryIDs.hasNext()) {
                    String missionaryID = missionaryIDs.next();
                    if (!missionaryID.contains("id")) {
                        String request = "http://" + Host_Name + ":800/apps/kardia/api/missionary/" + missionaryID + "/Notes?cx__mode=rest&cx__res_type=collection&cx__res_format=attrs&cx__res_attrs=basic";
                        String requestJSON = GET(request);
                        JSONObject prayerRequest = new JSONObject(requestJSON);
                        Iterator<String> requestKeys = prayerRequest.keys();
                        while (requestKeys.hasNext()) {
                            String prayerKey = requestKeys.next();
                            if (!prayerKey.equals("@id")) {
                                JSONObject noteJSON = prayerRequest.getJSONObject(prayerKey);

                                if(noteJSON.getString("note_type").equals("Pray")) {

                                    PrayerRequest tempRequest = new PrayerRequest();
                                    tempRequest.setId(noteJSON.getString("note_id"));
                                    tempRequest.setText(noteJSON.getString("note_text"));
                                    tempRequest.setSubject(noteJSON.getString("note_subject"));

                                    JSONObject date = new JSONObject(noteJSON.getString("note_date"));
                                    String day = date.getString("day");
                                    String month = date.getString("month");
                                    String year = date.getString("year");

                                    tempRequest.setDate(month + "-" + day + "-" + year);

                                    db.addRequest(tempRequest);

                                }
                                else if(noteJSON.getString("note_type").equals("Update"))
                                {
                                    Update tempUpdate = new Update();
                                    tempUpdate.setId(noteJSON.getString("note_id"));
                                    tempUpdate.setText(noteJSON.getString("note_text"));
                                    tempUpdate.setSubject(noteJSON.getString("note_subject"));

                                    JSONObject date = new JSONObject(noteJSON.getString("note_date"));
                                    String day = date.getString("day");
                                    String month = date.getString("month");
                                    String year = date.getString("year");

                                    tempUpdate.setDate(month + "-" + day + "-" + year);

                                    db.addUpdate(tempUpdate);
                                }
                            }
                        }
                    }
                }
            }

        }catch (Exception e){
            e.printStackTrace();
        }


    }
    /**
     * This pulls the years related to a specific fund.
     * If they are not yet stored adds relationships between the fund and year
     * (Notice only adds the relationship if one does not already exist)
     *
     * @param result, the JSON information returned from the back-end
     */
    private void loadFundYears(String result, int fundId){

        ArrayList<String> TestYearNamesList = db.getYearNames();
        ArrayList<String> TestYearConnection = db.getYearNamesFund(fundId);
        JSONObject json = null;
        try {
            json = new JSONObject(result);
        } catch (JSONException e1) {
            e1.printStackTrace();
        }
        JSONArray tempYears = json.names();

        for(int x = 0; x < tempYears.length(); x++){

            try{
                if(!tempYears.getString(x).equals("@id")){
                    JSONObject YearObj = json.getJSONObject(tempYears.getString(x));
                    JSONObject giftObj = YearObj.getJSONObject("gift_total");
                    int[] gifttotal = {
                            Integer.parseInt(giftObj.getString("wholepart")),
                            Integer.parseInt(giftObj.getString("fractionpart"))
                    };
                    String name = YearObj.getString("name");
                    if(gifttotal[1] >= 100){
                        gifttotal[1] /= 100;
                    }
                    Year temp = new Year();
                    temp.setName(name);
                    temp.setGift_total(gifttotal);

                    //if connection between fund and year doesn't exist... add new connection
                    if(TestYearNamesList.contains(name) && !TestYearConnection.contains(name)){
                        int yearid = db.getYear(name).getId();
                        db.addYear_Fund(yearid, fundId, gifttotal[0], gifttotal[1]);
                    }
                    //if the connection does exist, update the values
                    else if(TestYearNamesList.contains(name) && TestYearConnection.contains(name)){
                        int yearid = db.getYear(name).getId();
                        db.updateYear_Fund(yearid, fundId, gifttotal[0], gifttotal[1]);
                    }
                }
            }
            catch(JSONException e){
                e.printStackTrace();
            }
        }
    }

    /**
     * This formats the return json for every year related to an account
     * if the year and/or relationship do not exist they will be added.
     *
     * @param result
     */
    private void loadYears(String result){

        ArrayList<String> TestYearNamesList = db.getYearNames();
        ArrayList<String> YearsForAccount = db.getYearNamesAccount(Account_ID);
        JSONObject json = null;
        try {
            json = new JSONObject(result);
        } catch (JSONException e1) {
            e1.printStackTrace();
        }
        JSONArray tempYears = json.names();

        for(int x = 0; x < tempYears.length(); x++){

            try{
                if(!tempYears.getString(x).equals("@id")){
                    JSONObject YearObj = json.getJSONObject(tempYears.getString(x));
                    JSONObject giftObj = YearObj.getJSONObject("gift_total");
                    int[] gifttotal = {
                            Integer.parseInt(giftObj.getString("wholepart")),
                            Integer.parseInt(giftObj.getString("fractionpart"))
                    };
                    String name = YearObj.getString("name");

                    if(gifttotal[1] >= 100){
                        gifttotal[1] /= 100;
                    }

                    Year temp = new Year();
                    temp.setName(name);
                    temp.setGift_total(gifttotal);

                    if(!TestYearNamesList.contains(name)){ // if the year doesn't exist yet.. add it and add the relationship
                        db.addYear(temp);
                        int yearid = db.getLastId("year");
                        db.addYear_Account(yearid, Account_ID, gifttotal[0], gifttotal[1]);
                    }
                    else if(!YearsForAccount.contains(name)){ // if the year exists but isnt related yet.. add the relationship
                        int yearid = db.getYear(name).getId();
                        db.addYear_Account(yearid, Account_ID, gifttotal[0], gifttotal[1]);
                    }
                    else if(YearsForAccount.contains(name)){ // if the year and the relationship exist, update the values
                        int yearid = db.getYear(name).getId();
                        db.updateYear_Account(yearid, Account_ID, gifttotal[0], gifttotal[1]);
                    }
                }
            }
            catch(JSONException e){
                e.printStackTrace();
            }
        }
    }

    /**
     * This formats the return json into gifts and if the gift
     * was not already stored add it and a relationship to the related fund through the Fund_ID
     * and a relationship to the related year through the Year_ID
     *
     * @param result
     * @param Year_ID
     * @param Fund_ID
     */
    private void loadGifts(String result, int Year_ID, int Fund_ID){
        Log.w(Tag, "Loading Gifts For Fund " + Fund_ID);
        ArrayList<String> TestGiftNameList = db.getGiftNames(Fund_ID, Year_ID);
        JSONObject json = null;
        try {
            json = new JSONObject(result);
        } catch (JSONException e1) {
            e1.printStackTrace();
        }
        JSONArray tempGifts = json.names();

        for(int i = 0; i < tempGifts.length(); i++){
            try{
                if(!tempGifts.getString(i).equals("@id")){
                    JSONObject GiftObj = json.getJSONObject(tempGifts.getString(i));
                    JSONObject dateObj = GiftObj.getJSONObject("gift_date");
                    JSONObject amountObj = GiftObj.getJSONObject("gift_amount");
                    int[] gifttotal = {
                            Integer.parseInt(amountObj.getString("wholepart")),
                            Integer.parseInt(amountObj.getString("fractionpart"))
                    };
                    String name = GiftObj.getString("name");

                    String gift_fund = GiftObj.getString("gift_fund");
                    String gift_fund_desc = GiftObj.getString("gift_fund_desc");
                    String gift_year = dateObj.getString("year");
                    String gift_month = dateObj.getString("month");
                    gift_month = (gift_month.length() < 2)? "0" + gift_month : gift_month;
                    String gift_day = dateObj.getString("day");
                    gift_day = (gift_day.length() < 2)? "0" + gift_day : gift_day;
                    String gift_date = gift_year + "-"
                            + gift_month + "-" + gift_day;

                    String gift_check_num = GiftObj.getString("gift_check_num");

                    if(gifttotal[1] >= 100){
                        gifttotal[1] /= 100;
                    }

                    Gift temp = new Gift();
                    temp.setName(name);
                    temp.setGift_fund(gift_fund);
                    temp.setGift_fund_desc(gift_fund_desc);
                    Log.w(Tag, "Gift Date: " + gift_date);
                    temp.setGift_date(gift_date);
                    temp.setGift_check_num(gift_check_num);
                    temp.setGift_amount(gifttotal);

                    // if it didn't exist... add gift, connection to year, fund, and account
                    if(!TestGiftNameList.contains(temp.getName())){
                        db.addGift(temp);
                        int giftid = db.getLastId("gift");
                        db.addGift_Year(giftid, Year_ID);
                        db.addGift_Fund(giftid, Fund_ID);
                        db.addGift_Account(giftid, Account_ID);
                    }
                }
            }
            catch(JSONException e){
                e.printStackTrace();
            }
        }
    }
}