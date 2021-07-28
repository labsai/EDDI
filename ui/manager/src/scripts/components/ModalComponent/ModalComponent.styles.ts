import { makeStyles } from '@material-ui/core/styles';
import {
  BLACK_COLOR,
  BLUE_COLOR,
  DARK_GREY_COLOR,
  GREY_COLOR,
  LIGHT_GREY_COLOR,
  RED_COLOR,
  WHITE_COLOR,
} from '../../../styles/DefaultStylingProperties';

const DISCARD_MODAL_HEIGHT = 110;
const DISCARD_MODAL_WIDTH = 300;

const useStyles = makeStyles({
  close: {
    '&:focus': {
      color: '#000',
      cursor: 'pointer',
    },
    '&:hover': {
      color: '#000',
      cursor: 'pointer',
    },
    color: '#FFF',
    cursor: 'pointer',
    float: 'right',
    fontSize: '40px',
    position: 'relative',
    top: '-40px',
  },
  content: {
    color: GREY_COLOR,
    backgroundColor: DARK_GREY_COLOR,
    fontSize: '12px',
    width: '100%',
    textAlign: 'left',
    display: 'flex',
    flexDirection: 'column',
  },
  updateModalContent: {
    display: 'block',
    background: DARK_GREY_COLOR,
    borderRadius: '4px',
    fontSize: '10px',
    fontStyle: 'arial',
    marginBottom: '200px',
    maxWidth: '960px',
    minWidth: '600px',
    outline: 'none',
    padding: '0px',
    paddingBottom: '100px',
    position: 'relative',
    minHeight: '600px',
    maxHeight: 'auto',
    overflow: 'hidden',
  },
  createdOn: {
    left: '50px',
  },
  dateTime: {
    marginRight: '50px',
  },
  descriptors: {
    display: 'flex',
    flex: 1,
    marginTop: '20px',
    overflow: 'hidden',
  },
  descriptionHeaderText: {
    marginTop: '10px',
    fontSize: '20px',
  },
  descriptorsUpdate: {
    display: 'flex',
    fontSize: '14px',
    paddingTop: '5px',
    paddingBottom: '5px',
    flex: 1,
    overflow: 'hidden',
    maxWidth: '300px',
    maxHeight: '80px',
    wordBreak: 'break-word',
    whiteSpace: 'pre-wrap',
  },
  headerTextUpdate: {
    fontSize: '25px',
    maxWidth: '400px',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
  },
  modalBottomHeader: {
    marginLeft: '50px',
  },
  modalHeader: {
    backgroundColor: BLACK_COLOR,
    borderTopLeftRadius: '4px',
    borderTopRightRadius: '4px',
    height: '156px',
    width: '100%',
  },
  tallModalHeader: {
    backgroundColor: BLACK_COLOR,
    borderTopLeftRadius: '4px',
    borderTopRightRadius: '4px',
    height: '176px',
    width: '100%',
  },
  modalTopButton: {
    backgroundColor: BLUE_COLOR,
    border: '0px',
    borderRadius: '4px',
    color: '#FFFFFF',
    cursor: 'pointer',
    fontSize: '12px',
    height: '36px',
    marginLeft: '32px',
    marginTop: '8px',
    textAlign: 'center',
    width: '137px',
  },
  modalTopHeader: {
    color: WHITE_COLOR,
    display: 'flex',
    marginLeft: '50px',
    marginRight: '40px',
    paddingTop: '50px',
  },
  modalTopHeaderCenter: {
    flex: 1,
  },
  packageData: {
    backgroundColor: '#FFF',
    flex: 1,
    minHeight: '200px',
    minWidth: '500px',
    overflow: 'hidden',
    paddingLeft: '49px',
    paddingRight: '29px',
    whiteSpace: 'pre-wrap',
  },
  title: {
    color: GREY_COLOR,
    fontSize: '12px',
    height: '14px',
    whiteSpace: 'nowrap',
    width: 'fit-content',
  },
  versionDropDown: {
    marginLeft: '15px',
  },
  botHeaderText: {
    marginTop: '6px',
    color: WHITE_COLOR,
    height: '35px',
    fontSize: '28px',
  },
  botHeaderTextSmall: {
    fontSize: '20px',
    paddingRight: '20px',
  },
  createPackageHeaderText: {
    position: 'absolute',
    height: '35px',
    width: '350px',
    fontSize: '24px',
  },
  botText: {
    paddingTop: '5px',
    margin: '20px 50px',
    fontSize: '20px',
    fontStyle: 'Bold',
    color: LIGHT_GREY_COLOR,
  },
  inputBox: {
    marginTop: '5px',
    height: '150px',
    fontSize: '18px',
    border: '1.5px solid rgba(128, 128, 128, 0.3)',
    borderRadius: '5px',
    wordWrap: 'break-word',
    minWidth: '400px',
    maxWidth: '100%',
    resize: 'horizontal',
  },
  inputBoxName: {
    border: '1.5px solid rgba(128, 128, 128, 0.3)',
    fontSize: '18px',
    borderRadius: '5px',
    marginTop: '5px',
    minWidth: '400px',
    maxWidth: '100%',
    resize: 'horizontal',
  },
  inputBoxContent: {
    paddingTop: '5px',
    color: WHITE_COLOR,

    '& textarea': {
      backgroundColor: GREY_COLOR,

      '&::placeholder': {
        color: LIGHT_GREY_COLOR,
      },
    },
  },
  pluginText: {
    marginLeft: '50px',
    fontSize: '15px',
  },
  pluginSelector: {
    paddingTop: '10px',
    width: '100%',
  },
  discardChanges: {
    color: 'white',
    backgroundColor: RED_COLOR,
    border: 'transparent',
    transition: 'background-color 0.3s ease',

    '&:hover': {
      backgroundColor: 'transparent',
      border: `2px solid ${RED_COLOR}`,
      color: RED_COLOR,
      transition: 'background-color 0.3s ease',
    },
    '&:disabled': {
      cursor: 'not-allowed',
    },
  },
  backButton: {
    marginRight: '5px',
  },
  collapsibleButton: {
    '&:focus': {
      backgroundColor: '#f9d648',
      outline: 'none',
    },
    '&:hover': {
      backgroundColor: '#f9d648',
    },
    display: 'flex',
    width: '100%',
    fontSize: '18px',
    textAlign: 'left',
    padding: '8px 20px 8px 20px',
    backgroundColor: '#FADA5E',
    border: 'none',
    cursor: 'pointer',
  },
  collapsibleRightSign: {
    textAlign: 'right',
    flex: 1,
  },
  exampleData: {
    color: GREY_COLOR,
    fontSize: '14px',
    backgroundColor: '#fdf1bf',
    flex: 1,
    overflow: 'hidden',
    whiteSpace: 'pre-wrap',
    padding: '5px 20px 5px 20px',
  },
  createNewBotButton: {
    backgroundColor: BLUE_COLOR,
    border: '0px',
    borderRadius: '4px',
    color: '#FFFFFF',
    fontSize: '12px',
    height: '36px',
    marginLeft: '60%',
    marginTop: '8px',
    textAlign: 'center',
    minWidth: '100px',
  },
  createNewConfigModalButton: {
    cursor: 'pointer',
  },
  updatePackageCreateNewBotButton: {
    cursor: 'pointer',
    marginLeft: '32px',
  },
  button: {
    marginLeft: 'auto',
  },
  pluginList: {
    display: 'grid',
    marginTop: '20px',
    marginBottom: '20px',
    marginRight: '50px',
    marginLeft: '50px',
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(252px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
  greenButton: {
    backgroundColor: '#4BCA81',
    marginLeft: '10px',

    '&:hover': {
      backgroundColor: 'transparent',
      color: '#4BCA81',
      border: '2px solid #4BCA81',
    },
    '&:active': {
      backgroundColor: '#4BCA81',
    },
  },
  showViewJson: {
    marginRight: '10px',
  },
  createBotButton: {
    float: 'right',
    marginLeft: 'auto',
  },
  paper: {
    position: 'fixed',
    top: `calc(50% - ${DISCARD_MODAL_HEIGHT / 2}px)`,
    left: `calc(50% - ${DISCARD_MODAL_WIDTH / 2}px)`,
    width: `${DISCARD_MODAL_WIDTH}px`,
    height: `${DISCARD_MODAL_HEIGHT}px`,
    display: 'flex',
    flexDirection: 'column',
    justifyContent: 'center',
    alignItems: 'center',
    background: DARK_GREY_COLOR,
    boxShadow: `0 0 20px ${GREY_COLOR}`,
    padding: '20px',
    borderRadius: '4px',
    color: WHITE_COLOR,

    '& > div': {
      display: 'flex',
      flexDirection: 'row',
      justifyContent: 'space-between',
    },
  },
});

export default useStyles;
