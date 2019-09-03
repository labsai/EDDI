import { CSSProperties } from 'react';
import {
  DARK_GREY_COLOR,
  SMALL_FONT,
} from '../../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  conversationList: {
    margin: '20px 20px',
    paddingBottom: '150px',
  },
  title: {
    display: 'flex',
    color: DARK_GREY_COLOR,
    fontSize: SMALL_FONT,
    marginBottom: '2px',
  },
  stepSize: {
    marginLeft: 'auto',
    width: '80px',
    textAlign: 'center',
  },
  environment: {
    width: '80px',
    marginRight: '15px',
  },
  conversationState: {
    width: '110px',
    marginRight: '20px',
  },
  lastModifiedOn: {
    marginRight: '42px',
  },
  createdOn: {
    marginRight: '2px',
  },
};
export default styles;
