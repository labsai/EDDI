import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { getPostExample } from '../../utils/EddiConfigExampleData';
import Parser from '../../utils/Parser';
import useStyles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';

interface IPublicProps {
  type: string;
}

interface IPrivateProps extends IPublicProps {}

const JsonExample = ({ type }: IPrivateProps) => {
  const classes = useStyles();
  const [showExample, setShowExample] = React.useState(false);

  const onButtonClick = () => {
    setShowExample(!showExample);
  };

  const typeName = Parser.getPluginName(type, false);
  return (
    <div>
      <button onClick={onButtonClick} className={classes.collapsibleButton}>
        <div>{`${
          showExample ? 'Hide' : 'Show'
        } ${typeName.toLowerCase()} example data`}</div>
        <div className={classes.collapsibleRightSign}>
          {showExample ? '-' : '+'}
        </div>
      </button>
      {showExample && (
        <div className={classes.exampleData}>{getPostExample(type)}</div>
      )}
    </div>
  );
};

const ComposedJsonExample: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('JsonExample'),
)(JsonExample);

export default ComposedJsonExample;
